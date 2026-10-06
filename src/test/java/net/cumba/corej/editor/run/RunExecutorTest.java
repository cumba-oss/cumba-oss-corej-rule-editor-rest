package net.cumba.corej.editor.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.editor.corpus.CorpusLayout;
import net.cumba.corej.editor.engine.CdtService;
import net.cumba.corej.editor.engine.Json;
import net.cumba.corej.editor.testing.RemoteRepo;
import net.cumba.corej.editor.testing.TestStore;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.corej.ruletest.cdt.ruletest.SidecarResolver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The executor in-process: the engine's verdict on the fixture scenarios, the findings, the v2
 * report, the proposed expectations, and the refusals — a rule that does not load, a parked rule,
 * and the sidecar confinement the engine enforces ({@code ../}, absolute, symlink; §5.8.6).
 */
class RunExecutorTest
{

    static final List<RunJob.StandardRefJson> STANDARDS = List
            .of(new RunJob.StandardRefJson(TestStore.IG_KEY, "primary"));

    /** A suites tree with the rule's directory, like the exported scratch of a run. */
    static Path suites(Path aRoot) throws IOException
    {
        return Files.createDirectories(
                aRoot.resolve(CorpusLayout.scenarioDir("CDISC", RemoteRepo.RULE_B)));
    }


    static RunJob job(Path aRoot, String aCheck, String aCdt, @Nullable String aStore)
        throws IOException
    {
        Path dir = suites(aRoot);
        Path file = dir.resolve("buffer.cdt");
        Files.writeString(file, aCdt);
        return new RunJob(aCheck, null, STANDARDS, aCdt, file.toString(),
                aRoot.resolve(CorpusLayout.SCENARIOS).toString(), null, aStore, "test-engine");
    }


    @Test
    void aViolationScenarioPassesWithFindingsReportAndExpectations(@TempDir Path aRoot)
        throws IOException
    {
        RunReport r = RunExecutor.execute(job(aRoot, RemoteRepo.CHECK_B_TEXT,
                RemoteRepo.SCENARIO_B_INVALID_TEXT, TestStore.file().toString()));
        assertEquals("PASS", r.outcome(), r.detail());
        assertEquals("OK", r.code());
        assertTrue(r.scopeGate().inScope());
        assertNotNull(r.evaluation());
        assertTrue(r.evaluation().violated());
        assertTrue(r.evaluation().executed());
        assertEquals(1, r.evaluation().totalCount());
        assertNull(r.skip());
        assertNotNull(r.verdict());
        assertTrue(r.verdict().pass(), r.verdict().detail());
        assertEquals(1, r.findings().size());
        RunReport.Finding f = r.findings().get(0);
        assertEquals("AE", f.dataset());
        assertEquals(1, f.row());
        assertEquals("01-001", f.usubjid());
        assertEquals("HEADACHE", f.values().get("AEDECOD"));
        assertEquals(1, r.results().size());
        assertEquals("EXECUTED", r.results().get(0).status());
        assertNotNull(r.reportJson());
        JsonNode report = Json.parse(r.reportJson());
        assertTrue(report.has("Findings"), report.propertyNames().toString());
        assertEquals("test-engine",
                report.path("Conformance_Details").path("CORE_Engine_Version").asString(),
                report.path("Conformance_Details").toString());
        assertNotNull(r.expectations());
        assertEquals(1, r.expectations().count());
        assertEquals(1, r.expectations().ats());
        assertTrue(r.expectations().lines().get(0).startsWith("#expectViolationCount 1"),
                r.expectations().lines().toString());
        assertTrue(r.expectations().lines().get(1).startsWith("#expectViolationAt row=1"),
                r.expectations().lines().toString());
        assertEquals("violation", r.expectations().expect());
        assertEquals("#test CDISC-CG0270 expect=violation domain=AE", r.expectations().testLine(),
                "the verdict stays; the line is shown unchanged");
        assertEquals("inline", r.worker());
        assertEquals(List.of(), r.ruleDiagnostics());
    }


    /** The text "take actual as expected" writes, parsed by the engine's own scenario parser. */
    static RuleTestScenario confirmed(Path aRoot, String aCdt, RunReport.Expectations aProposal)
        throws IOException
    {
        String written = CdtService.withExpectations(aCdt, aProposal.lines(), aProposal.expect());
        assertTrue(written.contains(aProposal.testLine() + "\n"), written);
        Path file = suites(aRoot).resolve("confirmed.cdt");
        SidecarResolver sidecars = SidecarResolver.rootedAt(aRoot.resolve(CorpusLayout.SCENARIOS));
        return RuleTestCdt.parse(written, "confirmed.cdt", sidecars.includeResolver(file));
    }


    @Test
    void aRuleThatFiresOnANoViolationScenarioProposesExpectViolation(@TempDir Path aRoot)
        throws IOException
    {
        // REST review #2: the old proposal kept expect=noViolation beside #expectViolationAt
        // lines — a buffer the parser refuses
        String cdt = RemoteRepo.INITIAL_FILES.get(RemoteRepo.SCENARIO_B_VALID).replace(
                "STUDY1 | 01-001 | HEADACHE | HEADACHE", "STUDY1 | 01-001 | \"\" | HEADACHE");
        RunReport r = RunExecutor.execute(job(aRoot, RemoteRepo.CHECK_B_TEXT, cdt, null));
        assertEquals("FAIL", r.outcome(), r.detail());
        RunReport.Expectations e = r.expectations();
        assertNotNull(e, r.toString());
        assertEquals("violation", e.expect());
        assertEquals("#test CDISC-CG0270 expect=violation domain=AE", e.testLine());
        assertEquals(1, e.count());
        RuleTestScenario parsed = confirmed(aRoot, cdt, e);
        assertEquals(RuleTestScenario.Verdict.VIOLATION, parsed.getExpect());
        assertEquals(1, parsed.getExpectViolationCount());
        assertEquals(1, parsed.getExpectedViolations().size());
    }


    @Test
    void nothingFiringOnAViolationScenarioProposesNoViolationWithoutExpectations(
            @TempDir Path aRoot)
        throws IOException
    {
        String cdt = RemoteRepo.SCENARIO_B_INVALID_TEXT
                .replace("STUDY1 | 01-001 | 1 | \"\" | HEADACHE",
                        "STUDY1 | 01-001 | 1 | HEADACHE | HEADACHE")
                .replace("#expectViolationCount 1\n",
                        "#expectViolationCount 1\n#expectViolationAt row=1 AEDECOD=HEADACHE\n");
        RunReport r = RunExecutor.execute(job(aRoot, RemoteRepo.CHECK_B_TEXT, cdt, null));
        assertEquals("FAIL", r.outcome(), r.detail());
        RunReport.Expectations e = r.expectations();
        assertNotNull(e, r.toString());
        assertEquals("noViolation", e.expect());
        assertEquals(List.of(), e.lines(), "the expectations are cleared");
        assertNull(e.count());
        assertEquals("#test CDISC-CG0270 expect=noViolation domain=AE", e.testLine());
        RuleTestScenario parsed = confirmed(aRoot, cdt, e);
        assertEquals(RuleTestScenario.Verdict.NO_VIOLATION, parsed.getExpect());
        assertNull(parsed.getExpectViolationCount());
        assertEquals(List.of(), parsed.getExpectedViolations());
    }


    @Test
    void aFindingNamesTheDatasetTheEngineEvaluatedInAMultiDatasetScenario(@TempDir Path aRoot)
        throws IOException
    {
        // web review #11: the Results tab's dataset column. DM comes FIRST in the file; the rule
        // runs on the primary dataset (#test domain=AE), as the v2 report names it.
        String cdt = RemoteRepo.SCENARIO_B_INVALID_TEXT.replace("dataset AE label=",
                "dataset DM label=\"Demographics\"\ncol STUDYID type=Char\ncol USUBJID type=Char\n"
                        + "---\nSTUDY1 | 01-001\n---\ndataset AE label=");
        RunReport r = RunExecutor.execute(job(aRoot, RemoteRepo.CHECK_B_TEXT, cdt, null));
        assertEquals("PASS", r.outcome(), r.detail());
        assertEquals(1, r.findings().size());
        assertEquals("AE", r.findings().get(0).dataset());
        JsonNode report = Json.parse(r.reportJson());
        assertTrue(report.toString().contains("\"AE\""), "the JSON tab names the same dataset");
        assertEquals(r.findings(), Json.read(Json.write(r), RunReport.class).findings(),
                "the field crosses the worker's JSON");
    }


    @Test
    void aNoViolationScenarioPassesWithoutExpectations(@TempDir Path aRoot) throws IOException
    {
        RunReport r = RunExecutor.execute(job(aRoot, RemoteRepo.CHECK_B_TEXT,
                RemoteRepo.INITIAL_FILES.get(RemoteRepo.SCENARIO_B_VALID), null));
        assertEquals("PASS", r.outcome(), r.detail());
        assertNotNull(r.evaluation());
        assertFalse(r.evaluation().violated());
        assertEquals(List.of(), r.findings());
        assertNull(r.expectations());
        assertNotNull(r.reportJson(), "the report exists for an executed rule without findings");
    }


    @Test
    void aWrongExpectationFailsTheVerdict(@TempDir Path aRoot) throws IOException
    {
        String cdt = RemoteRepo.SCENARIO_B_INVALID_TEXT
                .replace("expect=violation", "expect=noViolation")
                .replace("#expectViolationCount 1\n", "");
        RunReport r = RunExecutor.execute(job(aRoot, RemoteRepo.CHECK_B_TEXT, cdt, null));
        assertEquals("FAIL", r.outcome());
        assertEquals("VERDICT", r.code(), r.detail());
        assertNotNull(r.verdict());
        assertFalse(r.verdict().pass());
        assertEquals(1, r.findings().size(), "the findings are still reported");
    }


    @Test
    void anOutOfScopeDatasetIsTheScopeGatesVerdict(@TempDir Path aRoot) throws IOException
    {
        String cdt = RemoteRepo.SCENARIO_B_INVALID_TEXT.replace("domain=AE", "domain=TS")
                .replace("dataset AE", "dataset TS");
        RunReport r = RunExecutor.execute(job(aRoot, RemoteRepo.CHECK_B_TEXT, cdt, null));
        assertEquals("FAIL", r.outcome());
        assertEquals("SCOPE", r.code(), r.detail());
        assertFalse(r.scopeGate().inScope());
        assertNotNull(r.scopeGate().mismatch());
    }


    @Test
    void aRuleThatDoesNotLoadAndAParkedRuleAreStoppedBeforeTheEngine(@TempDir Path aRoot)
        throws IOException
    {
        RunReport broken = RunExecutor.execute(job(aRoot,
                "Core:\n  Id: \"CDISC-CG0270\"\nCheck:\n  expression: >-\n    (AETERM ==\n",
                RemoteRepo.SCENARIO_B_INVALID_TEXT, null));
        assertEquals("FAIL", broken.outcome());
        assertEquals(RunExecutor.RULE_ERRORS, broken.code());
        assertFalse(broken.ruleDiagnostics().isEmpty());
        assertEquals(List.of(), broken.results());
        RunReport parked = RunExecutor.execute(job(aRoot,
                RemoteRepo.CHECK_B_TEXT.replace("Description:",
                        "Executability: \"Not Executable\"\nDescription:"),
                RemoteRepo.SCENARIO_B_INVALID_TEXT, null));
        assertEquals("FAIL", parked.outcome());
        assertEquals(RunExecutor.PARKED, parked.code());
    }


    @Test
    void aScenarioThatDoesNotParseIsAParseFailure(@TempDir Path aRoot) throws IOException
    {
        RunReport r = RunExecutor.execute(job(aRoot, RemoteRepo.CHECK_B_TEXT,
                "#!RuleTest\n#test CDISC-CG0270 expect=violation domain=AE\n"
                        + "dataset AE\ncol A type=Char\n---\n1 | 2\n---\n",
                null));
        assertEquals("FAIL", r.outcome());
        assertEquals("PARSE", r.code(), r.detail());
        assertNull(r.reportJson());
    }


    @Test
    void sidecarsOutsideTheTreeAreRefusedByTheEngine(@TempDir Path aRoot) throws IOException
    {
        Path secret = aRoot.resolve("secret.json");
        Files.writeString(secret, "{\"leak\": true}");
        String base = "#!RuleTest\n#test CDISC-CG0270 expect=violation domain=AE\n";
        String tail = "dataset AE\ncol AETERM type=Char\ncol AEDECOD type=Char\n---\n"
                + "\"\" | X\n---\n";
        RunReport up = RunExecutor.execute(job(aRoot, RemoteRepo.CHECK_B_TEXT,
                base + "#library-include ../../../../../../../secret.json\n" + tail, null));
        assertEquals("FAIL", up.outcome());
        assertTrue(List.of("PARSE", "SIDECAR").contains(up.code()), up.toString());
        assertFalse(up.detail().contains("leak"), "no file content in a message: " + up);
        assertFalse(up.detail().contains(aRoot.toString()),
                "a relative escape is reported tree-relative, never with the server's root: "
                        + up.detail());
        RunReport absolute = RunExecutor.execute(job(aRoot, RemoteRepo.CHECK_B_TEXT,
                base + "#library-include " + secret.toAbsolutePath() + "\n" + tail, null));
        assertEquals("FAIL", absolute.outcome());
        assertTrue(List.of("PARSE", "SIDECAR").contains(absolute.code()), absolute.toString());
        assertFalse(absolute.detail().contains("leak"), "no file content: " + absolute);
        assertTrue(absolute.detail().contains("must be relative"),
                "the engine names the refusal, echoing the path the scenario wrote: "
                        + absolute.detail());
        // a symlink planted inside the tree pointing out of it
        Path dir = suites(aRoot);
        Path link = dir.resolve("linked.json");
        try
        {
            Files.createSymbolicLink(link, secret);
        }
        catch (IOException | UnsupportedOperationException e)
        {
            return; // no symlinks on this file system: the two refusals above stand
        }
        RunReport r = RunExecutor.execute(job(aRoot, RemoteRepo.CHECK_B_TEXT,
                base + "#library-include linked.json\n" + tail, null));
        assertEquals("FAIL", r.outcome());
        assertTrue(List.of("PARSE", "SIDECAR").contains(r.code()), r.toString());
        assertFalse(r.detail().contains("leak"));
    }


    @Test
    void aMissingSidecarRootIsASidecarFailure(@TempDir Path aRoot) throws IOException
    {
        RunJob job = new RunJob(RemoteRepo.CHECK_B_TEXT, null, STANDARDS,
                RemoteRepo.SCENARIO_B_INVALID_TEXT, aRoot.resolve("nowhere/x.cdt").toString(),
                aRoot.resolve("nowhere").toString(), null, null, null);
        RunReport r = RunExecutor.execute(job);
        assertEquals("FAIL", r.outcome());
        assertEquals("SIDECAR", r.code());
    }


    @Test
    void theStoreAvailabilityFollowsThePath(@TempDir Path aDir) throws IOException
    {
        assertNull(RunExecutor.store(null).factory());
        assertFalse(RunExecutor.store(null).fatal());
        assertNull(RunExecutor.store(aDir.resolve("missing.zip").toString()).factory());
        Path garbage = aDir.resolve("garbage.zip");
        Files.writeString(garbage, "nope");
        assertNull(RunExecutor.store(garbage.toString()).factory());
        assertNotNull(RunExecutor.store(TestStore.file().toString()).factory());
    }


    @Test
    void theReportRoundTripsThroughJson(@TempDir Path aRoot) throws IOException
    {
        RunReport r = RunExecutor.execute(
                job(aRoot, RemoteRepo.CHECK_B_TEXT, RemoteRepo.SCENARIO_B_INVALID_TEXT, null));
        String json = Json.write(r);
        RunReport back = Json.read(json, RunReport.class);
        assertEquals(r, back);
        RunJob job = job(aRoot, "x", "y", null);
        assertEquals(job, Json.read(Json.write(job), RunJob.class));
    }
}
