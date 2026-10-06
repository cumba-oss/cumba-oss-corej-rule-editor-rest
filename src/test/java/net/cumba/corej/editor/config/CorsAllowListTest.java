package net.cumba.corej.editor.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import net.cumba.corej.editor.HomeStaysCleanExtension;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The CORS allow-list of PLAN-rule-editor §4.2 (phase 4): with {@code editor.cors.allowed-origins}
 * set, a listed origin's preflight and request are answered with credentials; an unlisted origin is
 * refused; and with the list EMPTY — the shipped default — no origin is ever allowed, which is what
 * the same-origin shapes rely on.
 */
class CorsAllowListTest
{

    static final String ALLOWED = "https://rule-editor.example.org";

    static final String OTHER = "https://evil.example.net";

    static void bindScratch(DynamicPropertyRegistry aRegistry, Path aRoot)
    {
        aRegistry.add("editor.repository.url", () -> "file://" + aRoot.resolve("acme/rules.git"));
        aRegistry.add("editor.workspace-root", () -> aRoot.resolve("workspaces").toString());
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ExtendWith(HomeStaysCleanExtension.class)
    class WithAllowList
    {

        @TempDir
        private static Path root;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry aRegistry)
        {
            bindScratch(aRegistry, root);
            aRegistry.add("editor.cors.allowed-origins[0]", () -> ALLOWED);
        }

        @Autowired
        private MockMvc mvc;

        @Test
        void listedOriginPreflightAndRequestAreAnsweredWithCredentials() throws Exception
        {
            mvc.perform(options("/api/info").header("Origin", ALLOWED)
                    .header("Access-Control-Request-Method", "PUT")
                    .header("Access-Control-Request-Headers", "X-CSRF-TOKEN, Content-Type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                    .andExpect(header().string("Access-Control-Allow-Headers",
                            "X-CSRF-TOKEN, Content-Type"));
            mvc.perform(get("/api/info").header("Origin", ALLOWED)).andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
        }


        @Test
        void unlistedOriginIsRefused() throws Exception
        {
            mvc.perform(options("/api/info").header("Origin", OTHER)
                    .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            mvc.perform(get("/api/info").header("Origin", OTHER)).andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }


    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ExtendWith(HomeStaysCleanExtension.class)
    class ShippedDefault
    {

        @TempDir
        private static Path root;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry aRegistry)
        {
            bindScratch(aRegistry, root);
        }

        @Autowired
        private MockMvc mvc;

        @Autowired
        private EditorProperties properties;

        @Test
        void noOriginIsAllowedWithTheListEmpty() throws Exception
        {
            org.junit.jupiter.api.Assertions.assertTrue(
                    properties.getCors().getAllowedOrigins().isEmpty(), "shipped default: empty");
            mvc.perform(options("/api/info").header("Origin", ALLOWED)
                    .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            // a same-origin request (no Origin header) is untouched
            mvc.perform(get("/api/info")).andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }
}
