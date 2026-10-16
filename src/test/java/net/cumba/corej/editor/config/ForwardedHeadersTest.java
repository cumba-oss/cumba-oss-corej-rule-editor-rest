package net.cumba.corej.editor.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import net.cumba.corej.editor.HomeStaysCleanExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The editor behind a TLS-terminating proxy (PLAN-rule-editor-forwarded-headers): the browser's
 * {@code Origin} is {@code https://host}, the proxy talks plain HTTP to the editor, so without the
 * forwarded scheme every request carrying an {@code Origin} (the login, every save) was refused as
 * a cross-origin request — 403 {@code Invalid CORS request}. {@link TrustedForwardedHeadersFilter}
 * believes {@code X-Forwarded-Proto/-Host/-Port} from a peer in {@code editor.trusted-proxies}
 * only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(HomeStaysCleanExtension.class)
@SuppressWarnings("PMD.AvoidUsingHardCodedIP") // documentation range (RFC 5737)
class ForwardedHeadersTest
{

    static final String PROXY = "192.0.2.10";

    static final String STRANGER = "192.0.2.66";

    static final String HOST = "editor.example.org";

    /** What Spring's {@code DefaultCorsProcessor} answers a refused cross-origin request. */
    static final String CORS_REFUSAL = "Invalid CORS request";

    static final String LOGIN_BODY = "{\"login\":\"mw\",\"token\":\"wrong\"}";

    static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    private static Path root;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry aRegistry)
    {
        CorsAllowListTest.bindScratch(aRegistry, root);
        aRegistry.add("editor.trusted-proxies[0]", () -> PROXY);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TrustedForwardedHeadersFilter filter;

    @Autowired
    private Environment environment;

    /** The request as the TLS proxy's nginx hands it on: from {@code aPeer}, plain HTTP. */
    static MockHttpServletRequestBuilder fromPeer(MockHttpServletRequestBuilder aBuilder,
            String aPeer)
    {
        return aBuilder.with(r ->
        {
            r.setRemoteAddr(aPeer);
            return r;
        });
    }


    /** The login behind TLS: {@code Host: h}, {@code Origin: https://h}, no CSRF token. */
    static MockHttpServletRequestBuilder loginBehindTls(String aPeer)
    {
        return fromPeer(post("/api/session/login"), aPeer).header("Host", HOST)
                .header("Origin", "https://" + HOST).header("X-Forwarded-Proto", "https")
                .contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY);
    }


    static String code(MvcResult aResult) throws Exception
    {
        return JSON.readTree(aResult.getResponse().getContentAsString()).path("code").asString();
    }


    static void assertCorsRefused(MvcResult aResult) throws Exception
    {
        assertEquals(403, aResult.getResponse().getStatus());
        assertEquals(CORS_REFUSAL, aResult.getResponse().getContentAsString());
    }


    @Test
    void behindATrustedTlsProxyTheLoginPassesCors() throws Exception
    {
        // ① CORS runs before CSRF: the CSRF refusal proves the request passed CORS
        MvcResult login = mvc.perform(loginBehindTls(PROXY)).andReturn();
        assertNotEquals(CORS_REFUSAL, login.getResponse().getContentAsString(),
                "refused as a cross-origin request: " + login.getResponse().getStatus());
        assertEquals(403, login.getResponse().getStatus());
        assertEquals("CSRF", code(login), login.getResponse().getContentAsString());
        MvcResult info = mvc
                .perform(fromPeer(get("/api/info"), PROXY).header("Host", HOST)
                        .header("Origin", "https://" + HOST).header("X-Forwarded-Proto", "https"))
                .andReturn();
        assertEquals(200, info.getResponse().getStatus(), info.getResponse().getContentAsString());
        // a TLS proxy on :8443 that keeps Host and forwards only the scheme
        MvcResult port = mvc.perform(fromPeer(post("/api/session/login"), PROXY)
                .header("Host", HOST + ":8443").header("Origin", "https://" + HOST + ":8443")
                .header("X-Forwarded-Proto", "https").contentType(MediaType.APPLICATION_JSON)
                .content(LOGIN_BODY)).andReturn();
        assertEquals("CSRF", code(port), port.getResponse().getContentAsString());
    }


    @Test
    void fromAnUntrustedPeerTheForwardedSchemeIsNotBelieved() throws Exception
    {
        // ② the same request from a peer outside editor.trusted-proxies
        assertCorsRefused(mvc.perform(loginBehindTls(STRANGER)).andReturn());
        assertCorsRefused(mvc
                .perform(fromPeer(get("/api/info"), STRANGER).header("Host", HOST)
                        .header("Origin", "https://" + HOST).header("X-Forwarded-Proto", "https"))
                .andReturn());
    }


    @Test
    void plainHttpOnItsOwnPortStaysSameOrigin() throws Exception
    {
        // ③ today's working shape: http://localhost:8080 through the SPA's nginx, which forwards
        // the scheme but no port — the port stays the Host's (never nginx's own listen 80)
        MvcResult login = mvc.perform(fromPeer(post("/api/session/login"), PROXY)
                .header("Host", "localhost:8080").header("Origin", "http://localhost:8080")
                .header("X-Forwarded-Proto", "http").contentType(MediaType.APPLICATION_JSON)
                .content(LOGIN_BODY)).andReturn();
        assertNotEquals(CORS_REFUSAL, login.getResponse().getContentAsString());
        assertEquals("CSRF", code(login), login.getResponse().getContentAsString());
    }


    @Test
    void forwardedAndTheOtherHeadersAreNeverRead() throws Exception
    {
        // ④ Forwarded: proto=https alone changes nothing (only X-Forwarded-Proto/-Host/-Port)
        assertCorsRefused(mvc.perform(fromPeer(post("/api/session/login"), PROXY)
                .header("Host", HOST).header("Origin", "https://" + HOST)
                .header("Forwarded", "proto=https;host=" + HOST)
                .contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY)).andReturn());
        assertCorsRefused(
                mvc.perform(fromPeer(post("/api/session/login"), PROXY).header("Host", HOST)
                        .header("Origin", "https://" + HOST).header("X-Forwarded-Ssl", "on")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY)).andReturn());
        // ... and X-Forwarded-Prefix changes neither the path nor the outcome
        MockHttpServletRequest request = behindTls(PROXY);
        request.addHeader("X-Forwarded-Prefix", "/elsewhere");
        HttpServletRequest seen = filtered(request);
        assertEquals("/api/info", seen.getRequestURI());
        assertEquals("", seen.getContextPath());
        assertEquals("https://" + HOST + "/api/info", seen.getRequestURL().toString());
        MvcResult info = mvc.perform(fromPeer(get("/api/info"), PROXY).header("Host", HOST)
                .header("Origin", "https://" + HOST).header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Prefix", "/elsewhere")).andReturn();
        assertEquals(200, info.getResponse().getStatus(), info.getResponse().getContentAsString());
    }


    /** {@code GET /api/info} as the TLS proxy hands it on, from {@code aPeer}. */
    static MockHttpServletRequest behindTls(String aPeer)
    {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/info");
        request.setRemoteAddr(aPeer);
        request.addHeader("Host", HOST);
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-For", "203.0.113.1, 198.51.100.7");
        return request;
    }


    /** The request the rest of the chain sees behind the filter. */
    private HttpServletRequest filtered(MockHttpServletRequest aRequest) throws Exception
    {
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(aRequest, response, (req, res) -> seen.set(req));
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return (HttpServletRequest) seen.get();
    }


    @Test
    void theWrapperChangesTheOriginAndNothingElse() throws Exception
    {
        HttpServletRequest seen = filtered(behindTls(PROXY));
        assertEquals("https", seen.getScheme());
        assertTrue(seen.isSecure());
        assertEquals(HOST, seen.getServerName());
        assertEquals(443, seen.getServerPort());
        assertEquals("https://" + HOST + "/api/info", seen.getRequestURL().toString());
        // the peer and the headers as they arrived: ClientAddress walks X-Forwarded-For from them
        assertEquals(PROXY, seen.getRemoteAddr());
        assertEquals("203.0.113.1, 198.51.100.7", seen.getHeader("X-Forwarded-For"));
        assertEquals("https", seen.getHeader("X-Forwarded-Proto"));
        // a forwarded host with a port, and a forwarded port
        MockHttpServletRequest hostPort = behindTls(PROXY);
        hostPort.addHeader("X-Forwarded-Host", "outer.example.org:8443");
        HttpServletRequest a = filtered(hostPort);
        assertEquals("outer.example.org", a.getServerName());
        assertEquals(8443, a.getServerPort());
        assertEquals("https://outer.example.org:8443/api/info", a.getRequestURL().toString());
        MockHttpServletRequest port = behindTls(PROXY);
        port.addHeader("X-Forwarded-Port", "8443");
        assertEquals(8443, filtered(port).getServerPort());
        // a TLS proxy on a port of its own keeps Host (h:8443) and forwards only the scheme
        MockHttpServletRequest tlsPort = behindTls(PROXY);
        tlsPort.removeHeader("Host");
        tlsPort.addHeader("Host", "outer.example.org:8443");
        assertEquals("https://outer.example.org:8443/api/info",
                filtered(tlsPort).getRequestURL().toString());
        // plain http forwarded keeps the Host's port
        MockHttpServletRequest http = new MockHttpServletRequest("GET", "/api/info");
        http.setRemoteAddr(PROXY);
        http.addHeader("Host", "localhost:8080");
        http.addHeader("X-Forwarded-Proto", "http");
        HttpServletRequest b = filtered(http);
        assertEquals("http", b.getScheme());
        assertFalse(b.isSecure());
        assertEquals(8080, b.getServerPort());
        assertEquals("http://localhost:8080/api/info", b.getRequestURL().toString());
    }


    @Test
    void bootsOwnForwardedHeaderHandlingIsPinnedOff()
    {
        // review M2: unset, Boot turns the RemoteIpValve on by itself on a detected cloud
        // platform — a second, unconfigured trust path beside editor.trusted-proxies
        assertEquals("none", environment.getProperty("server.forward-headers-strategy"));
    }


    @Test
    void behindATrustedTlsProxyTheEditorSendsNoHsts() throws Exception
    {
        // review L1: the forwarded request is secure now, and Spring Security's default header
        // writer would add Strict-Transport-Security to it — the TLS proxy owns HSTS, not the app
        MvcResult info = mvc
                .perform(fromPeer(get("/api/info"), PROXY).header("Host", HOST)
                        .header("Origin", "https://" + HOST).header("X-Forwarded-Proto", "https"))
                .andReturn();
        assertEquals(200, info.getResponse().getStatus(), info.getResponse().getContentAsString());
        assertNull(info.getResponse().getHeader("Strict-Transport-Security"));
        // a direct https request neither; the other default security headers stay
        MvcResult direct = mvc.perform(get("/api/info").secure(true)).andReturn();
        assertNull(direct.getResponse().getHeader("Strict-Transport-Security"));
        assertEquals("nosniff", direct.getResponse().getHeader("X-Content-Type-Options"));
    }


    @Test
    void theExplicitPortOfAHostValue()
    {
        assertEquals(8080, TrustedForwardedHeadersFilter.Forwarded.explicitPort("localhost:8080"));
        assertEquals(8443, TrustedForwardedHeadersFilter.Forwarded.explicitPort("[::1]:8443"));
        assertEquals(80, TrustedForwardedHeadersFilter.Forwarded.explicitPort(" h:80 "));
        for (String none : new String[]
        {
                "h", "h:", "[::1]", "::1", "2001:db8::1", ""
        })
        {
            assertEquals(-1, TrustedForwardedHeadersFilter.Forwarded.explicitPort(none), none);
        }
        assertEquals(-1, TrustedForwardedHeadersFilter.Forwarded.explicitPort(null));
    }


    @Test
    void anUntrustedPeerOrNoForwardedHeaderPassesTheRequestThroughUnchanged() throws Exception
    {
        MockHttpServletRequest stranger = behindTls(STRANGER);
        assertSame(stranger, filtered(stranger));
        MockHttpServletRequest plain = new MockHttpServletRequest("GET", "/api/info");
        plain.setRemoteAddr(PROXY);
        assertSame(plain, filtered(plain));
    }


    @Test
    void forwardedValuesThatDoNotParseAreABadRequest() throws Exception
    {
        for (String port : new String[]
        {
                "nope", "0", "70000"
        })
        {
            MvcResult r = mvc
                    .perform(fromPeer(get("/api/info"), PROXY).header("Host", HOST)
                            .header("X-Forwarded-Proto", "https").header("X-Forwarded-Port", port))
                    .andReturn();
            assertEquals(400, r.getResponse().getStatus(), port);
            assertEquals("BAD_REQUEST", code(r), port);
        }
        // from an untrusted peer they are not read at all
        MvcResult r = mvc
                .perform(fromPeer(get("/api/info"), STRANGER).header("Host", HOST)
                        .header("X-Forwarded-Proto", "https").header("X-Forwarded-Port", "nope"))
                .andReturn();
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    }
}
