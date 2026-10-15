package net.cumba.corej.editor.web;

import static net.cumba.corej.editor.web.ApiTestSupport.CSRF_HEADER;
import static net.cumba.corej.editor.web.ApiTestSupport.body;
import static net.cumba.corej.editor.web.ApiTestSupport.csrf;
import static net.cumba.corej.editor.web.ApiTestSupport.json;
import static net.cumba.corej.editor.web.ApiTestSupport.login;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.ServletContext;
import net.cumba.corej.editor.HomeStaysCleanExtension;
import net.cumba.corej.editor.engine.Json;
import net.cumba.corej.editor.testing.FakeForge;
import net.cumba.corej.editor.testing.RemoteRepo;
import net.cumba.corej.editor.web.dto.Dtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * The request-level limits and the session hardening through the security chain: the read gate
 * before the body (security review M2), the body limit (M2), the login rate limit (L9), the short
 * pre-login session (L2), the CSRF rotation at login (INFO), the masked login body (L1), and a code
 * on Spring MVC's own 400 / 404 problems (REST review #9).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiTestSupport.Fakes.class)
@ExtendWith(HomeStaysCleanExtension.class)
class LimitsApiTest
{

    static final int MAX_BUFFER = 1000;

    /** The proxies of {@link #aForgedForwardedClientNeverGetsAFreshLoginBucket}. */
    static final String TRUSTED_PROXIES = "192.0.2.0/24";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry aRegistry)
    {
        aRegistry.add("editor.repository.url", () -> ApiTestSupport.remote().url());
        aRegistry.add("editor.workspace-root",
                () -> ApiTestSupport.root().resolve("workspaces-limits").toString());
        aRegistry.add("editor.forge.api-url", () -> "http://127.0.0.1:1/api/v1");
        aRegistry.add("editor.anonymous.enabled", () -> "false");
        aRegistry.add("editor.limits.max-buffer-bytes", () -> String.valueOf(MAX_BUFFER));
        aRegistry.add("editor.limits.logins-per-minute", () -> "5");
        aRegistry.add("editor.limits.pre-login-session-timeout", () -> "PT2M");
        aRegistry.add("editor.trusted-proxies[0]", () -> TRUSTED_PROXIES);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RequestSizeFilter sizeFilter;

    private static String code(MvcResult aResult) throws Exception
    {
        return body(aResult).path("code").asString();
    }


    @Test
    void readsAreRefusedBeforeTheirBodyIsReadWithoutASession() throws Exception
    {
        // security review M2: the permitAll POSTs deserialised the whole body before the 401
        MockHttpSession none = new MockHttpSession();
        String token = csrf(mvc, none);
        for (String endpoint : new String[]
        {
                "/api/run", "/api/analyse", "/api/cdt/parse", "/api/cdt/splice",
                "/api/cdt/expectations"
        })
        {
            MvcResult r = mvc.perform(json(post(endpoint), none, token, "{\"content\": [[["))
                    .andReturn();
            assertEquals(401, r.getResponse().getStatus(),
                    endpoint + ": refused before the malformed body was read");
            assertEquals("UNAUTHENTICATED", code(r));
        }
        MvcResult ct = mvc.perform(get("/api/engine/ct").param("codelist", "SEX").session(none))
                .andReturn();
        assertEquals(401, ct.getResponse().getStatus(), "the store lookup is a read too");
    }


    @Test
    void aBodyPastTheLimitIs413BeforeAnyoneReadsIt() throws Exception
    {
        assertEquals(4L * MAX_BUFFER, sizeFilter.limit(), "four buffers' worth by default");
        MockHttpSession s = login(mvc, "mw", FakeForge.WRITE_TOKEN);
        String token = csrf(mvc, s);
        String big = "{\"path\":\"" + RemoteRepo.CHECK_B + "\",\"content\":"
                + Json.write("#".repeat(5 * MAX_BUFFER)) + "}";
        MvcResult r = mvc.perform(json(post("/api/analyse"), s, token, big)).andReturn();
        assertEquals(413, r.getResponse().getStatus());
        assertEquals("TOO_LARGE", code(r));
        // under the request limit but over the buffer limit: the endpoint's own, finer check
        String buffer = "{\"path\":\"" + RemoteRepo.CHECK_B + "\",\"content\":"
                + Json.write("#".repeat(MAX_BUFFER + 1)) + "}";
        MvcResult b = mvc.perform(json(post("/api/analyse"), s, token, buffer)).andReturn();
        assertEquals(413, b.getResponse().getStatus());
        assertTrue(body(b).path("detail").asString().contains("content is"), body(b).toString());
    }

    /** A POST that declares no length — a chunked upload, which the filter must count. */
    static final class Undeclared extends AbstractMockHttpServletRequestBuilder<Undeclared>
    {

        Undeclared(String aUri)
        {
            super(HttpMethod.POST);
            uri(aUri);
        }


        @Override
        protected MockHttpServletRequest createServletRequest(ServletContext aContext)
        {
            return new MockHttpServletRequest(aContext)
            {

                @Override
                public long getContentLengthLong()
                {
                    return -1;
                }


                @Override
                public int getContentLength()
                {
                    return -1;
                }
            };
        }
    }

    @Test
    void aBodyWithoutALengthIsCountedAndCutOffAt413() throws Exception
    {
        // review: the counting stream (no Content-Length) through the whole chain
        MockHttpSession s = login(mvc, "mw", FakeForge.WRITE_TOKEN);
        String token = csrf(mvc, s);
        String big = "{\"path\":\"" + RemoteRepo.CHECK_B + "\",\"content\":"
                + Json.write("#".repeat(5 * MAX_BUFFER)) + "}";
        MvcResult r = mvc.perform(new Undeclared("/api/analyse").session(s)
                .header(CSRF_HEADER, token).contentType(MediaType.APPLICATION_JSON).content(big))
                .andReturn();
        assertEquals(413, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals("TOO_LARGE", code(r));
        // a body under the limit, undeclared too, is read normally
        MvcResult small = mvc
                .perform(new Undeclared("/api/analyse").session(s).header(CSRF_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"" + RemoteRepo.CHECK_B + "\",\"content\":"
                                + Json.write(RemoteRepo.CHECK_B_TEXT.substring(0, 200)) + "}"))
                .andReturn();
        assertEquals(200, small.getResponse().getStatus(),
                small.getResponse().getContentAsString());
    }


    @Test
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP") // a test client's address
    void loginsAreLimitedPerClient() throws Exception
    {
        // security review L9; its own client address, so the other tests' logins do not count
        MockHttpSession s = new MockHttpSession();
        String token = csrf(mvc, s);
        for (int i = 0; i < 5; i++)
        {
            MvcResult refused = mvc.perform(json(post("/api/session/login"), s, token,
                    "{\"login\":\"mw\",\"token\":\"wrong\"}").with(r ->
                    {
                        r.setRemoteAddr("10.9.8.7");
                        return r;
                    })).andReturn();
            assertEquals("FORGE_REFUSED", code(refused), "attempt " + i);
        }
        MvcResult limited = mvc.perform(json(post("/api/session/login"), s, token,
                "{\"login\":\"mw\",\"token\":\"" + FakeForge.WRITE_TOKEN + "\"}").with(r ->
                {
                    r.setRemoteAddr("10.9.8.7");
                    return r;
                })).andReturn();
        assertEquals(429, limited.getResponse().getStatus());
        assertEquals("RATE_LIMITED", code(limited));
        MvcResult other = mvc.perform(
                json(post("/api/session/login"), s, token, "{\"login\":\"mw\",\"token\":\"wrong\"}")
                        .with(r ->
                        {
                            r.setRemoteAddr("10.9.8.8");
                            return r;
                        }))
                .andReturn();
        assertEquals("FORGE_REFUSED", code(other), "another client is not limited");
    }


    /** A login from {@code aPeer} carrying the forwarding headers {@code aHeaders}. */
    private MvcResult loginVia(MockHttpSession aSession, String aToken, String aPeer,
            String... aHeaders)
        throws Exception
    {
        var request = json(post("/api/session/login"), aSession, aToken,
                "{\"login\":\"mw\",\"token\":\"wrong\"}").with(r ->
                {
                    r.setRemoteAddr(aPeer);
                    return r;
                });
        for (int i = 0; i < aHeaders.length; i += 2)
        {
            request.header(aHeaders[i], aHeaders[i + 1]);
        }
        return mvc.perform(request).andReturn();
    }


    @Test
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP") // documentation ranges (RFC 5737)
    void aForgedForwardedClientNeverGetsAFreshLoginBucket() throws Exception
    {
        // PLAN-rule-editor-forwarded-headers M1, end to end through the filter chain: behind a
        // trusted proxy the client is the right-most untrusted X-Forwarded-For hop, whatever the
        // client claims left of it or in Forwarded: for= — never a fresh bucket per request (as
        // Spring's ForwardedHeaderFilter would hand out, by making the left-most claim the peer)
        MockHttpSession s = new MockHttpSession();
        String token = csrf(mvc, s);
        for (int i = 0; i < 5; i++)
        {
            MvcResult refused = loginVia(s, token, "192.0.2.10", "X-Forwarded-For",
                    "203.0.113." + (i + 1) + ", 198.51.100.7");
            assertEquals("FORGE_REFUSED", code(refused), "X-Forwarded-For attempt " + i);
        }
        MvcResult limited = loginVia(s, token, "192.0.2.10", "X-Forwarded-For",
                "203.0.113.99, 198.51.100.7");
        assertEquals("RATE_LIMITED", code(limited), "a forged left-most hop is no new client");
        for (int i = 0; i < 5; i++)
        {
            MvcResult refused = loginVia(s, token, "192.0.2.10", "Forwarded",
                    "for=203.0.113." + (i + 1), "X-Forwarded-For", "198.51.100.8");
            assertEquals("FORGE_REFUSED", code(refused), "Forwarded attempt " + i);
        }
        MvcResult forwarded = loginVia(s, token, "192.0.2.10", "Forwarded", "for=203.0.113.99",
                "X-Forwarded-For", "198.51.100.8");
        assertEquals("RATE_LIMITED", code(forwarded), "Forwarded: for= is never believed");
    }


    @Test
    void aPreLoginSessionIsShortAndTheLoginRotatesItsCsrfToken() throws Exception
    {
        MockHttpSession s = new MockHttpSession();
        String before = csrf(mvc, s);
        assertEquals(120, s.getMaxInactiveInterval(), "security review L2: short until a login");
        mvc.perform(post("/api/session/login").session(s).header(CSRF_HEADER, before)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"mw\",\"token\":\"" + FakeForge.WRITE_TOKEN + "\"}"))
                .andReturn();
        assertEquals(8 * 3600, s.getMaxInactiveInterval(), "the login raises it");
        // security review INFO: the token seen before the login is worthless after it
        MvcResult stale = mvc
                .perform(json(post("/api/cdt/parse"), s, before,
                        "{\"content\":" + Json.write(RemoteRepo.SCENARIO_B_INVALID_TEXT) + "}"))
                .andReturn();
        assertEquals(403, stale.getResponse().getStatus());
        assertEquals("CSRF", code(stale));
        String after = csrf(mvc, s);
        assertNotEquals(before, after);
        assertEquals(8 * 3600, s.getMaxInactiveInterval(), "a signed-in session stays long");
        MvcResult fresh = mvc
                .perform(json(post("/api/cdt/parse"), s, after,
                        "{\"content\":" + Json.write(RemoteRepo.SCENARIO_B_INVALID_TEXT) + "}"))
                .andReturn();
        assertEquals(200, fresh.getResponse().getStatus());
    }


    @Test
    void theLoginBodyNeverPrintsItsToken()
    {
        // security review L1: Spring logs request bodies at DEBUG through toString
        String text = new Dtos.LoginRequest("mw", "s3cret-token").toString();
        assertFalse(text.contains("s3cret-token"), text);
        assertTrue(text.contains("mw"), text);
    }


    @Test
    void springsOwnProblemsCarryACode() throws Exception
    {
        // REST review #9: an unreadable body, a mistyped parameter, an unknown path
        MockHttpSession s = login(mvc, "rv", FakeForge.READ_TOKEN);
        String token = csrf(mvc, s);
        MvcResult notJson = mvc.perform(json(post("/api/analyse"), s, token, "{\"path\": ["))
                .andReturn();
        assertEquals(400, notJson.getResponse().getStatus());
        assertEquals("BAD_REQUEST", code(notJson));
        MvcResult mistyped = mvc
                .perform(get("/api/search").param("q", "x").param("limit", "many").session(s))
                .andReturn();
        assertEquals(400, mistyped.getResponse().getStatus());
        assertEquals("BAD_REQUEST", code(mistyped));
        MvcResult unknown = mvc.perform(get("/api/no-such-endpoint").session(s)).andReturn();
        assertEquals(404, unknown.getResponse().getStatus());
        assertEquals("NOT_FOUND", code(unknown));
        // a missing expectation list is a 400, never "clear them all"
        MvcResult noLines = mvc
                .perform(json(post("/api/cdt/expectations"), s, token,
                        "{\"content\":" + Json.write(RemoteRepo.SCENARIO_B_INVALID_TEXT) + "}"))
                .andReturn();
        assertEquals(400, noLines.getResponse().getStatus());
        assertEquals("BAD_REQUEST", code(noLines));
    }
}
