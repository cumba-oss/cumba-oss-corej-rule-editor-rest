package net.cumba.corej.editor.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.cumba.corej.editor.testing.FakeForge;
import net.cumba.corej.editor.testing.RemoteRepo;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the MVC tests share: a bare remote under a temp root (created once per JVM, lazily, so a
 * {@code @DynamicPropertySource} can point {@code editor.repository.url} at it), the fake forge
 * bean, and the real CSRF-then-login flow of the SPA.
 */
final class ApiTestSupport
{

    static final JsonMapper JSON = JsonMapper.builder().build();

    static final String CSRF_HEADER = "X-CSRF-TOKEN";

    private static Path root;

    private static RemoteRepo remote;

    private ApiTestSupport()
    {
    }

    /** The fake forge beside the configured one; {@code @Primary} so every injection takes it. */
    @TestConfiguration
    static class Fakes
    {

        @Bean
        @Primary
        FakeForge fakeForge()
        {
            return new FakeForge();
        }
    }

    static synchronized Path root()
    {
        if (root == null)
        {
            try
            {
                // under the test cwd (target/test-cwd in a Maven run), so `mvn clean` wipes it
                root = Files.createTempDirectory(Path.of("").toAbsolutePath(), "rule-editor-api-");
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        }
        return root;
    }


    static synchronized RemoteRepo remote()
    {
        if (remote == null)
        {
            try
            {
                remote = RemoteRepo.create(root().resolve("remote"));
            }
            catch (IOException | GitAPIException e)
            {
                throw new IllegalStateException(e);
            }
        }
        return remote;
    }


    static String csrf(MockMvc aMvc, MockHttpSession aSession) throws Exception
    {
        MvcResult r = aMvc.perform(get("/api/session/csrf").session(aSession))
                .andExpect(status().isOk()).andReturn();
        return JSON.readTree(r.getResponse().getContentAsString()).path("token").asString();
    }


    /** The SPA's login: fetch the CSRF token, POST the credentials with it. */
    static MockHttpSession login(MockMvc aMvc, String aLogin, String aToken) throws Exception
    {
        MockHttpSession session = new MockHttpSession();
        String csrf = csrf(aMvc, session);
        aMvc.perform(post("/api/session/login").session(session).header(CSRF_HEADER, csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"" + aLogin + "\",\"token\":\"" + aToken + "\"}"))
                .andExpect(status().isOk());
        return session;
    }


    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder aBuilder,
            MockHttpSession aSession, String aCsrf, String aBody)
    {
        return aBuilder.session(aSession).header(CSRF_HEADER, aCsrf)
                .contentType(MediaType.APPLICATION_JSON).content(aBody);
    }


    static JsonNode body(MvcResult aResult) throws Exception
    {
        return JSON.readTree(aResult.getResponse().getContentAsString());
    }
}
