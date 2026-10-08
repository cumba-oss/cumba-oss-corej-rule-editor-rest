package net.cumba.corej.editor.web;

import static net.cumba.corej.editor.web.ApiTestSupport.body;
import static net.cumba.corej.editor.web.ApiTestSupport.login;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.cumba.corej.editor.HomeStaysCleanExtension;
import net.cumba.corej.editor.engine.StoreSeedSource;
import net.cumba.corej.editor.engine.StoreSeeding;
import net.cumba.corej.editor.testing.FakeForge;
import net.cumba.corej.editor.testing.TestStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * The start-up seed through the running service (owner, 2026-10-08): the context starts and serves
 * while the (fake) source runs, {@code /api/info} says the store is being seeded, and once it is in
 * the info, the CT lookup and the analysis use it — without a restart. The API key configured here
 * never appears in a response.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(
{
        ApiTestSupport.Fakes.class, StoreSeedingApiTest.Seed.class
})
@ExtendWith(HomeStaysCleanExtension.class)
class StoreSeedingApiTest
{

    static final String KEY = "api-key-never-served-4711";

    static final CountDownLatch RELEASE = new CountDownLatch(1);

    /** A source writing the fixture store once released — no network. */
    @TestConfiguration
    static class Seed
    {

        @Bean
        @Primary
        StoreSeedSource fakeSeedSource()
        {
            return (aRequest, aStaging) ->
            {
                try
                {
                    assertTrue(RELEASE.await(60, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                TestStore.write(aStaging, "seeded at start-up");
                return new StoreSeedSource.Outcome("fixture", 2, 1, List.of(), null);
            };
        }
    }

    static Path storeFile()
    {
        return ApiTestSupport.root().resolve("seeded-store").resolve("metadata-store.zip");
    }


    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry aRegistry)
    {
        aRegistry.add("editor.repository.url", () -> ApiTestSupport.remote().url());
        aRegistry.add("editor.workspace-root",
                () -> ApiTestSupport.root().resolve("workspaces-seeding").toString());
        aRegistry.add("editor.forge.api-url", () -> "http://localhost:1/api/v1");
        aRegistry.add("editor.anonymous.enabled", () -> "false");
        aRegistry.add("editor.metadata-store", () -> storeFile().toString());
        aRegistry.add("editor.store.seed.type", () -> "API");
        aRegistry.add("editor.store.seed.api-key", () -> KEY);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StoreSeeding seeding;

    @Test
    void theServiceServesWhileSeedingAndUsesTheStoreOnceItIsIn() throws Exception
    {
        assertFalse(Files.exists(storeFile()));
        String info = mvc.perform(get("/api/info")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertFalse(info.contains(KEY), info);
        JsonNode engine = ApiTestSupport.JSON.readTree(info).path("engine");
        assertFalse(engine.path("storeAvailable").asBoolean());
        assertEquals("metadata store is being seeded (API)…",
                engine.path("storeReason").asString());
        // the structured fields the SPA codes against (never the prose of storeReason)
        assertTrue(engine.path("seeding").isBoolean(), info);
        assertTrue(engine.path("seeding").asBoolean(), "a seed runs");
        assertEquals("seeding (API)…", engine.path("seedStatus").asString());
        MockHttpSession s = login(mvc, "mw", FakeForge.WRITE_TOKEN);
        assertEquals(0,
                body(mvc.perform(
                        get("/api/engine/ct").param("codelist", TestStore.SEX_CODELIST).session(s))
                        .andReturn()).path("hits").size(),
                "no store yet: no hits");

        RELEASE.countDown();
        seeding.current().orElseThrow().get(60, TimeUnit.SECONDS);

        info = mvc.perform(get("/api/info")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertFalse(info.contains(KEY), info);
        engine = ApiTestSupport.JSON.readTree(info).path("engine");
        assertTrue(engine.path("storeAvailable").asBoolean(), info);
        assertEquals("", engine.path("storeReason").asString());
        assertEquals(5, engine.path("storeFormat").asInt());
        assertFalse(engine.path("seeding").asBoolean(), "the seed is over");
        assertTrue(engine.path("seeding").isBoolean(), info);
        assertEquals("", engine.path("seedStatus").asString(), "complete: nothing to say");
        assertEquals(TestStore.CT_PACKAGE,
                body(mvc.perform(
                        get("/api/engine/ct").param("codelist", TestStore.SEX_CODELIST).session(s))
                        .andReturn()).path("hits").path(0).path("ctPackage").asString(),
                "the store is used at once");
        assertTrue(Files.isRegularFile(storeFile()));
        assertTrue(Files.isRegularFile(storeFile().resolveSibling("metadata-store.zip.seed.json")));
    }
}
