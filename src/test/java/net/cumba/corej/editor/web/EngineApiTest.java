package net.cumba.corej.editor.web;

import static net.cumba.corej.editor.web.ApiTestSupport.body;
import static net.cumba.corej.editor.web.ApiTestSupport.csrf;
import static net.cumba.corej.editor.web.ApiTestSupport.json;
import static net.cumba.corej.editor.web.ApiTestSupport.login;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.List;
import net.cumba.corej.editor.HomeStaysCleanExtension;
import net.cumba.corej.editor.corpus.CorpusLayout;
import net.cumba.corej.editor.engine.Json;
import net.cumba.corej.editor.testing.FakeForge;
import net.cumba.corej.editor.testing.RemoteRepo;
import net.cumba.corej.editor.testing.TestStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The engine's endpoints end to end over MockMvc with the security chain (phase 3): analysis,
 * catalogues, scenarios, related rules, the grid, and a run through a real worker — including the
 * sidecar confinement as the SPA would hit it (§5.8.6), the buffer limit (§5.8.5) and read-only
 * mode running without saving (O12).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiTestSupport.Fakes.class)
@ExtendWith(HomeStaysCleanExtension.class)
class EngineApiTest
{

    static final int MAX_BYTES = 4000;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry aRegistry)
    {
        aRegistry.add("editor.repository.url", () -> ApiTestSupport.remote().url());
        aRegistry.add("editor.workspace-root",
                () -> ApiTestSupport.root().resolve("workspaces-engine").toString());
        aRegistry.add("editor.forge.api-url", () -> "http://127.0.0.1:1/api/v1");
        aRegistry.add("editor.anonymous.enabled", () -> "false");
        aRegistry.add("editor.metadata-store", () ->
        {
            try
            {
                return TestStore.file().toString();
            }
            catch (IOException e)
            {
                throw new IllegalStateException(e);
            }
        });
        aRegistry.add("editor.limits.max-buffer-bytes", () -> String.valueOf(MAX_BYTES));
        aRegistry.add("editor.run.timeout", () -> "PT120S");
        aRegistry.add("editor.run.max-heap", () -> "256m");
    }

    @Autowired
    private MockMvc mvc;

    private static String code(MvcResult aResult) throws Exception
    {
        return body(aResult).path("code").asString();
    }


    private static String quote(String aText)
    {
        return Json.write(aText);
    }


    @Test
    void theInfoAndTheExplorerCarryTheLanguageLevels() throws Exception
    {
        MockHttpSession s = login(mvc, "mw", FakeForge.WRITE_TOKEN);
        mvc.perform(get("/api/info")).andExpect(status().isOk())
                .andExpect(jsonPath("$.engine.languageLevel").value(1))
                .andExpect(jsonPath("$.engine.storeAvailable").value(true))
                .andExpect(jsonPath("$.engine.storeFormat").value(5))
                .andExpect(jsonPath("$.engine.maxBufferBytes").value(MAX_BYTES))
                .andExpect(jsonPath("$.engine.runTimeoutSeconds").value(120));
        mvc.perform(get("/api/explorer").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.languageLevel").value(1));
    }


    @Test
    void analyseAnswersDiagnosticsDerivedFieldsAndTheScope() throws Exception
    {
        MockHttpSession s = login(mvc, "mw", FakeForge.WRITE_TOKEN);
        String csrf = csrf(mvc, s);
        MvcResult ok = mvc
                .perform(json(post("/api/analyse"), s, csrf,
                        "{\"path\":\"" + RemoteRepo.CHECK_B + "\",\"content\":"
                                + quote(RemoteRepo.CHECK_B_TEXT) + "}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode a = body(ok).path("analysis");
        assertEquals(RemoteRepo.RULE_B, a.path("coreId").asString());
        assertTrue(a.path("loaded").asBoolean());
        assertTrue(a.path("storeAvailable").asBoolean());
        assertTrue(a.path("derived").path("outputVariables").path("effective").size() >= 2);
        assertTrue(a.path("scope").path("variables").size() > 5);
        // the docs buffer, when sent, is analysed instead of the sidecar
        MvcResult docs = mvc
                .perform(json(post("/api/analyse"), s, csrf,
                        "{\"path\":\"" + RemoteRepo.CHECK_A + "\",\"content\":"
                                + quote(RemoteRepo.INITIAL_FILES.get(RemoteRepo.CHECK_A))
                                + ",\"docs\":\"Core: [\\n\"}"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(body(docs).path("analysis").path("diagnostics").toString().contains("DOCS"));
        // a buffer outside the allow-list, an oversize buffer, and no session
        assertEquals("BAD_PATH",
                code(mvc.perform(json(post("/api/analyse"), s, csrf,
                        "{\"path\":\"pom.xml\",\"content\":\"x\"}"))
                        .andExpect(status().isBadRequest()).andReturn()));
        assertEquals("TOO_LARGE", code(mvc
                .perform(json(post("/api/analyse"), s, csrf,
                        "{\"path\":\"" + RemoteRepo.CHECK_B + "\",\"content\":"
                                + quote("#".repeat(MAX_BYTES + 1)) + "}"))
                .andExpect(status().is(413)).andReturn()));
        MockHttpSession none = new MockHttpSession();
        assertEquals("UNAUTHENTICATED",
                code(mvc.perform(json(post("/api/analyse"), none, csrf(mvc, none),
                        "{\"path\":\"" + RemoteRepo.CHECK_B + "\",\"content\":\"x\"}"))
                        .andExpect(status().isUnauthorized()).andReturn()));
    }


    @Test
    void theCataloguesComeFromTheBranchAndTheStore() throws Exception
    {
        MockHttpSession s = login(mvc, "rv", FakeForge.READ_TOKEN);
        MvcResult functions = mvc.perform(get("/api/engine/functions").session(s))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = body(functions).path("functions");
        assertTrue(list.size() > 50, "every registry function: " + list.size());
        JsonNode dsExists = null;
        for (JsonNode f : list)
        {
            if ("ds_exists".equals(f.path("name").asString()))
            {
                dsExists = f;
            }
        }
        assertTrue(dsExists != null, "ds_exists is in the registry");
        assertEquals("True when the named dataset is present in the submission.",
                dsExists.path("summary").asString(), "the help text of the branch's functions.md");
        assertEquals("boolean", dsExists.path("returnType").asString());
        assertTrue(dsExists.path("signature").asString().startsWith("ds_exists("));
        mvc.perform(get("/api/engine/schema").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.properties.Core.description").value("The rule's identity."));
        mvc.perform(get("/api/engine/ct").param("codelist", TestStore.SEX_CODELIST).session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].submissionValue").value("SEX"))
                .andExpect(jsonPath("$.hits[0].ctPackage").value(TestStore.CT_PACKAGE))
                .andExpect(jsonPath("$.hits[0].termCount").value(3))
                .andExpect(jsonPath("$.hits[0].terms.length()").value(3));
        mvc.perform(get("/api/engine/ct").param("codelist", "sex").param("term", "F").session(s))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hits[0].terms.length()").value(1))
                .andExpect(jsonPath("$.hits[0].terms[0].preferredTerm").value("Female"));
        mvc.perform(get("/api/engine/ct").param("codelist", "C00000").session(s))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hits.length()").value(0));
    }


    @Test
    void scenariosRelatedAndTheSemanticSearchReadTheView() throws Exception
    {
        MockHttpSession s = login(mvc, "mw", FakeForge.WRITE_TOKEN);
        mvc.perform(get("/api/scenarios").param("rule", RemoteRepo.RULE_B).session(s))
                .andExpect(status().isOk()).andExpect(jsonPath("$.family").value("CDISC"))
                .andExpect(jsonPath("$.suite.scenarios.length()").value(2))
                .andExpect(jsonPath("$.suite.scenarios[0].expect").value("violation"))
                .andExpect(jsonPath("$.suite.scenarios[0].datasets[0]").value("AE"));
        assertEquals("BAD_REQUEST",
                code(mvc.perform(get("/api/scenarios").param("rule", "../x").session(s))
                        .andExpect(status().isBadRequest()).andReturn()));
        mvc.perform(get("/api/related").param("rule", RemoteRepo.RULE_B).session(s))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rule").value(RemoteRepo.RULE_B))
                .andExpect(jsonPath("$.related").isArray());
        mvc.perform(get("/api/search").param("variable", "AETERM").session(s))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.hits[0].id").value(RemoteRepo.RULE_B));
        mvc.perform(get("/api/search").param("function", "ds_exists").param("q", "ADSL").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].id").value(RemoteRepo.RULE_A));
        mvc.perform(get("/api/search").param("noScenarios", "true").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].id").value(RemoteRepo.RULE_F));
    }


    @Test
    void theGridParsesSplicesAndWritesExpectations() throws Exception
    {
        MockHttpSession s = login(mvc, "rv", FakeForge.READ_TOKEN);
        String csrf = csrf(mvc, s);
        MvcResult parsed = mvc
                .perform(json(post("/api/cdt/parse"), s, csrf,
                        "{\"content\":" + quote(RemoteRepo.SCENARIO_B_INVALID_TEXT) + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.datasets[0].name").value("AE"))
                .andExpect(jsonPath("$.datasets[0].rows.length()").value(2)).andReturn();
        JsonNode dataset = body(parsed).path("datasets").get(0);
        ((tools.jackson.databind.node.ArrayNode) dataset.path("rows"))
                .add(Json.parse("[\"STUDY1\",\"01-009\",\"9\",\"\",\"RASH\"]"));
        MvcResult spliced = mvc
                .perform(json(post("/api/cdt/splice"), s, csrf,
                        "{\"content\":" + quote(RemoteRepo.SCENARIO_B_INVALID_TEXT)
                                + ",\"dataset\":" + dataset + "}"))
                .andExpect(status().isOk()).andReturn();
        String text = body(spliced).path("content").asString();
        assertTrue(text.contains("01-009"), text);
        assertTrue(text.startsWith("#!RuleTest\n#test CDISC-CG0270"), text);
        ((tools.jackson.databind.node.ArrayNode) dataset.path("rows"))
                .add(Json.parse("[\"a\\nb\",\"x\",\"1\",\"\",\"\"]"));
        assertEquals("CDT_UNREPRESENTABLE", code(mvc
                .perform(json(post("/api/cdt/splice"), s, csrf,
                        "{\"content\":" + quote(RemoteRepo.SCENARIO_B_INVALID_TEXT)
                                + ",\"dataset\":" + dataset + "}"))
                .andExpect(status().isBadRequest()).andReturn()));
        MvcResult expectations = mvc
                .perform(json(post("/api/cdt/expectations"), s, csrf,
                        "{\"content\":" + quote(RemoteRepo.SCENARIO_B_INVALID_TEXT)
                                + ",\"lines\":[\"#expectViolationCount 1\","
                                + "\"#expectViolationAt row=1 AEDECOD=HEADACHE\"]}"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(body(expectations).path("content").asString()
                .contains("#expectViolationCount 1\n#expectViolationAt row=1 AEDECOD=HEADACHE\n"));
    }


    @Test
    void aReadOnlyUserRunsABufferThroughAWorkerAndSidecarsStayConfined() throws Exception
    {
        MockHttpSession s = login(mvc, "rv", FakeForge.READ_TOKEN);
        String csrf = csrf(mvc, s);
        String edited = RemoteRepo.SCENARIO_B_INVALID_TEXT
                .replace("NAUSEA | NAUSEA", "\"\" | NAUSEA")
                .replace("#expectViolationCount 1", "#expectViolationCount 2");
        MvcResult run = mvc
                .perform(json(post("/api/run"), s, csrf,
                        runBody(RemoteRepo.CHECK_B_TEXT, RemoteRepo.SCENARIO_B_INVALID, edited)))
                .andExpect(status().isOk()).andReturn();
        JsonNode report = body(run).path("report");
        assertEquals("PASS", report.path("outcome").asString(), report.toString());
        assertEquals("child", report.path("worker").asString());
        assertEquals(2, report.path("findings").size(), "the unsaved buffer ran: two findings");
        assertEquals(2, report.path("expectations").path("count").asInt());
        assertFalse(report.path("reportJson").asString().isEmpty());
        // the saved file is untouched: a read-only user never writes (O12)
        mvc.perform(
                get("/api/files/content").param("path", RemoteRepo.SCENARIO_B_INVALID).session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(RemoteRepo.SCENARIO_B_INVALID_TEXT));
        // sidecars: a relative escape and an absolute path, as the engine refuses them
        String base = "#!RuleTest\n#test CDISC-CG0270 expect=violation domain=AE\n";
        String tail = "dataset AE\ncol AETERM type=Char\ncol AEDECOD type=Char\n---\n\"\" | X\n"
                + "---\n";
        for (String include : List.of("#library-include ../../../../../../../../etc/passwd\n",
                "#library-include /etc/passwd\n"))
        {
            MvcResult refused = mvc
                    .perform(
                            json(post("/api/run"), s, csrf,
                                    runBody(RemoteRepo.CHECK_B_TEXT, RemoteRepo.SCENARIO_B_INVALID,
                                            base + include + tail)))
                    .andExpect(status().isOk()).andReturn();
            JsonNode r = body(refused).path("report");
            assertEquals("FAIL", r.path("outcome").asString(), include + " -> " + r);
            assertTrue(List.of("PARSE", "SIDECAR").contains(r.path("code").asString()),
                    include + " -> " + r);
            assertFalse(r.path("detail").asString().contains("root:"), "no file content");
        }
        // a scenario path outside the suites, and an oversize buffer
        assertEquals("BAD_PATH",
                code(mvc.perform(json(post("/api/run"), s, csrf,
                        runBody(RemoteRepo.CHECK_B_TEXT, RemoteRepo.CHECK_A, edited)))
                        .andExpect(status().isBadRequest()).andReturn()));
        assertEquals("TOO_LARGE", code(mvc
                .perform(json(post("/api/run"), s, csrf,
                        runBody(RemoteRepo.CHECK_B_TEXT, RemoteRepo.SCENARIO_B_INVALID,
                                edited + "# " + "x".repeat(MAX_BYTES) + "\n")))
                .andExpect(status().is(413)).andReturn()));
        // a rule that does not load is stopped without a worker
        MvcResult broken = mvc.perform(json(post("/api/run"), s, csrf,
                runBody("Core:\n  Id: \"CDISC-CG0270\"\nCheck:\n  expression: >-\n    (AETERM ==\n",
                        RemoteRepo.SCENARIO_B_INVALID, edited)))
                .andExpect(status().isOk()).andReturn();
        assertEquals("RULE_ERRORS", body(broken).path("report").path("code").asString());
        assertTrue(body(broken).path("report").path("ruleDiagnostics").size() > 0);
    }


    private static String runBody(String aCheck, String aScenarioPath, String aCdt)
    {
        return "{\"path\":\"" + RemoteRepo.CHECK_B + "\",\"content\":" + quote(aCheck)
                + ",\"scenarioPath\":\"" + aScenarioPath + "\",\"scenarioContent\":" + quote(aCdt)
                + "}";
    }


    @Test
    void aNewRulesScenarioRunsFromTheSuiteDirectoryItWillBeSavedIn() throws Exception
    {
        MockHttpSession s = login(mvc, "mw", FakeForge.WRITE_TOKEN);
        String csrf = csrf(mvc, s);
        String newRule = RemoteRepo.CHECK_B_TEXT.replace("CDISC-CG0270", "CDISC-CG0271");
        String scenario = RemoteRepo.SCENARIO_B_INVALID_TEXT.replace("CDISC-CG0270",
                "CDISC-CG0271");
        String path = CorpusLayout.scenarioDir("CDISC", "CDISC-CG0271") + "/new.cdt";
        MvcResult run = mvc
                .perform(json(post("/api/run"), s, csrf,
                        "{\"path\":\"rules-src/checks/CDISC/CDISC-CG0271.yaml\",\"content\":"
                                + quote(newRule) + ",\"scenarioPath\":\"" + path
                                + "\",\"scenarioContent\":" + quote(scenario) + "}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("PASS", body(run).path("report").path("outcome").asString(),
                body(run).toString());
        mvc.perform(get("/api/status").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.uncommitted.length()").value(0));
    }
}
