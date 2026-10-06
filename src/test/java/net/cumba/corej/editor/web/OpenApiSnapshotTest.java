package net.cumba.corej.editor.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.cumba.corej.editor.config.OpenApiConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The committed {@code openapi.snapshot.json} IS the served contract: the SPA (the web repository)
 * generates its TypeScript types from the file, so a served document that drifts from it reds here.
 * Regenerate deliberately with
 * {@code mvn -Dopenapi.snapshot.write=true test -Dtest=OpenApiSnapshotTest} and commit the result
 * with the change that caused it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiTestSupport.Fakes.class)
class OpenApiSnapshotTest
{

    static final String SNAPSHOT = "openapi.snapshot.json";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry aRegistry)
    {
        aRegistry.add("editor.repository.url", () -> ApiTestSupport.remote().url());
        aRegistry.add("editor.workspace-root",
                () -> ApiTestSupport.root().resolve("workspaces-openapi").toString());
        aRegistry.add("editor.forge.api-url", () -> "http://127.0.0.1:1/api/v1");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void apiDocsExposeTheContractAndTheSnapshotMatches() throws Exception
    {
        String served = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value(OpenApiConfig.TITLE))
                .andExpect(jsonPath("$.info.version").value(OpenApiConfig.API_VERSION))
                .andExpect(jsonPath("$.paths['/api/session/login']").exists())
                .andExpect(jsonPath("$.paths['/api/files/content']").exists())
                .andExpect(jsonPath("$.paths['/api/push']").exists())
                .andExpect(jsonPath("$.paths['/api/explorer']").exists())
                .andExpect(jsonPath("$.paths['/api/search']").exists()).andReturn().getResponse()
                .getContentAsString();
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
        JsonNode tree = mapper.readTree(served);
        String canonical = mapper.writeValueAsString(mapper.treeToValue(tree, Object.class)) + "\n";

        String basedir = System.getProperty("projectBasedir");
        Assumptions.assumeTrue(basedir != null, "not a Maven run");
        Path snapshot = Path.of(basedir).resolve(SNAPSHOT);
        if (Boolean.getBoolean("openapi.snapshot.write"))
        {
            Files.writeString(snapshot, canonical, StandardCharsets.UTF_8);
        }
        assertTrue(Files.exists(snapshot), "the committed OpenAPI snapshot is missing: " + snapshot
                + " (write it with -Dopenapi.snapshot.write=true)");
        assertEquals(Files.readString(snapshot, StandardCharsets.UTF_8), canonical,
                "the served OpenAPI document drifted from " + SNAPSHOT
                        + ": regenerate it with -Dopenapi.snapshot.write=true and commit it "
                        + "with the API change");
    }


    @Test
    void swaggerUiIsReachable() throws Exception
    {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }
}
