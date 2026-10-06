package net.cumba.corej.editor.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * {@code /brand/**} through the real MVC stack with the synthetic bundled SPA of the test classpath
 * ({@code src/test/resources/static}): the packaged brand by default, an external directory
 * outranking it, a missing file 404, a path that leaves the folder refused.
 */
class BrandServingTest
{

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ExtendWith(HomeStaysCleanExtension.class)
    class Packaged
    {

        @TempDir
        private static Path root;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry aRegistry)
        {
            CorsAllowListTest.bindScratch(aRegistry, root);
        }

        @Autowired
        private MockMvc mvc;

        @Test
        void servesThePackagedNeutralBrandWithNoCache() throws Exception
        {
            mvc.perform(get("/brand/brand.css")).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-cache"))
                    .andExpect(content().string("/* neutral - the synthetic test brand */\n"));
        }


        @Test
        void aMissingFileIs404AndTheFolderIsNotLeft() throws Exception
        {
            mvc.perform(get("/brand/missing.css")).andExpect(status().isNotFound());
            // the other packaged brand is not reachable through the mapping: Spring refuses a
            // path with ".." (400) before the resource handler sees it, encoded or not
            mvc.perform(get("/brand/../brands/p300/brand.css"))
                    .andExpect(status().is4xxClientError());
            mvc.perform(get("/brand/..%2Fbrands%2Fp300%2Fbrand.css"))
                    .andExpect(status().is4xxClientError());
        }
    }


    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ExtendWith(HomeStaysCleanExtension.class)
    class External
    {

        @TempDir
        private static Path root;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry aRegistry) throws IOException
        {
            CorsAllowListTest.bindScratch(aRegistry, root);
            Path p300 = Files.createDirectories(root.resolve("brands/p300"));
            Files.writeString(p300.resolve("brand.css"), "/* external p300 */",
                    StandardCharsets.UTF_8);
            Files.writeString(p300.resolve("logo.svg"), "<svg/>", StandardCharsets.UTF_8);
            aRegistry.add("editor.brand.name", () -> "p300");
            aRegistry.add("editor.brand.directory", () -> root.resolve("brands").toString());
        }

        @Autowired
        private MockMvc mvc;

        @Test
        void theExternalFolderOutranksThePackagedBrandOfTheSameName() throws Exception
        {
            mvc.perform(get("/brand/brand.css")).andExpect(status().isOk())
                    .andExpect(content().string("/* external p300 */"));
            mvc.perform(get("/brand/logo.svg")).andExpect(status().isOk())
                    .andExpect(content().string("<svg/>"));
        }
    }
}
