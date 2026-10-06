package net.cumba.corej.editor.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.cumba.corej.editor.testing.MapSource;
import net.cumba.corej.editor.testing.RemoteRepo;
import net.cumba.corej.editor.testing.TestStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The analysis of a buffer against the miniature corpus and the fixture store: diagnostics with
 * ranges, the derived fields, the scope universe and its variable catalogue, the referenced
 * variables, and the parked-rule suppression of {@code SENSITIVITY_UNDERIVABLE} (§12).
 */
class RuleAnalyserTest
{

    private static EngineStore store;

    private static PackageStandards standards;

    private static RuleAnalyser analyser;

    @BeforeAll
    static void open() throws IOException
    {
        store = new EngineStore(TestStore.file());
        standards = new PackageStandards();
        analyser = new RuleAnalyser(store, standards);
    }


    @AfterAll
    static void close() throws IOException
    {
        store.close();
        standards.close();
    }


    private static MapSource corpus()
    {
        return new MapSource(RemoteRepo.INITIAL_FILES);
    }


    @Test
    void aLoadedRuleHasDerivedFieldsScopeAndReferences() throws IOException
    {
        Analysis a = analyser.analyse(corpus(), RemoteRepo.CHECK_B, RemoteRepo.CHECK_B_TEXT, null);
        assertEquals(RemoteRepo.RULE_B, a.coreId());
        assertTrue(a.loaded());
        assertFalse(a.parked());
        assertTrue(a.storeAvailable());
        assertEquals(List.of(),
                a.diagnostics().stream().filter(d -> "ERROR".equals(d.severity())).toList(),
                "no error on a sound rule");
        Analysis.Derived derived = a.derived();
        assertNotNull(derived);
        assertNotNull(derived.sensitivity());
        assertTrue(derived.outputVariables().effective().containsAll(List.of("AEDECOD", "AETERM")),
                derived.outputVariables().toString());
        assertEquals(List.of(TestStore.IG_KEY), a.scope().products());
        assertEquals(List.of(), a.scope().missingProducts());
        Optional<Analysis.ScopeDataset> ae = a.scope().datasets().stream()
                .filter(d -> "AE".equals(d.name())).findFirst();
        assertTrue(ae.isPresent() && ae.get().inScope(), "AE is in scope");
        Optional<Analysis.ScopeDataset> ts = a.scope().datasets().stream()
                .filter(d -> "TS".equals(d.name())).findFirst();
        assertTrue(ts.isPresent() && !ts.get().inScope() && ts.get().mismatch() != null,
                "TS is judged out of scope with the axis named");
        Map<String, Analysis.Variable> vars = a.scope().variables().stream()
                .collect(java.util.stream.Collectors.toMap(Analysis.Variable::name, v -> v));
        assertTrue(vars.containsKey("AETERM"), vars.keySet().toString());
        assertEquals("Reported Term for the Adverse Event", vars.get("AETERM").label());
        assertEquals("Char", vars.get("AETERM").type());
        assertEquals("Req", vars.get("AETERM").core());
        assertTrue(vars.get("AETERM").datasets().contains("AE"));
        assertFalse(vars.containsKey("TSVAL"), "an out-of-scope dataset's variables are absent");
        Analysis.Variable term = vars.get("--TERM");
        assertNotNull(term, "the model's class variable in -- form: " + vars.keySet());
        assertTrue(term.classLevel());
        assertEquals(List.of("AETERM"), term.concrete());
        assertTrue(
                a.referencedVariables().stream().map(Analysis.VariableRef::name).toList()
                        .containsAll(List.of("AEDECOD", "AETERM")),
                a.referencedVariables().toString());
        assertEquals(List.of(), a.bindings());
        assertEquals(List.of(), a.requiredVariables());
    }


    @Test
    void docsBufferOverridesTheSidecarAndItsErrorsPointIntoTheDocsDocument() throws IOException
    {
        String docsFromBuffer = "Core: \"CDISC-AD0001\"\nCitations: [unterminated\n";
        Analysis a = analyser.analyse(corpus(), RemoteRepo.CHECK_A,
                RemoteRepo.INITIAL_FILES.get(RemoteRepo.CHECK_A), docsFromBuffer);
        assertTrue(a.diagnostics().stream().anyMatch(d -> "DOCS".equals(d.document())),
                "the docs buffer's syntax error is a DOCS diagnostic: " + a.diagnostics());
        Analysis b = analyser.analyse(corpus(), RemoteRepo.CHECK_A,
                RemoteRepo.INITIAL_FILES.get(RemoteRepo.CHECK_A), null);
        assertFalse(b.diagnostics().stream().anyMatch(d -> "DOCS".equals(d.document())),
                "the committed sidecar is sound");
    }


    @Test
    void aYamlSyntaxErrorIsRangedAndLoadsNoRule() throws IOException
    {
        Analysis a = analyser.analyse(corpus(), RemoteRepo.CHECK_B,
                "Core:\n  Id: \"CDISC-CG0270\"\nCheck: [broken\n", null);
        assertFalse(a.loaded());
        assertNull(a.derived());
        Analysis.Diagnostic error = a.diagnostics().stream()
                .filter(d -> "ERROR".equals(d.severity())).findFirst().orElseThrow();
        assertEquals("YAML_SYNTAX", error.code());
        assertEquals("CHECK", error.document());
        assertNotNull(error.range());
        assertTrue(error.range().startLine() >= 1, "1-based line: " + error.range());
    }


    @Test
    void anExpressionErrorIsRangedOnTheOffendingCharacter() throws IOException
    {
        String text = RemoteRepo.CHECK_B_TEXT.replace("AEDECOD != \"\" and AETERM == \"\"",
                "AEDECOD != \"\" and (AETERM == \"\"");
        Analysis a = analyser.analyse(corpus(), RemoteRepo.CHECK_B, text, null);
        assertTrue(
                a.diagnostics().stream()
                        .anyMatch(d -> d.range() != null && "ERROR".equals(d.severity())
                                && d.path() != null && d.path().contains("Check")),
                a.diagnostics().toString());
    }


    @Test
    void aParkedRuleIsReportedParkedWithoutTheSensitivityError() throws IOException
    {
        // A Check the classifier cannot derive a Sensitivity for (no operands it knows): on a
        // packaged rule that is SENSITIVITY_UNDERIVABLE; parked, the gate never raises it (§12).
        String parked = """
                Core:
                  Id: "CDISC-CG9999"
                  Status: "Draft"
                Description: "Parked."
                Executability: "Not Executable"
                Scope:
                  Domains:
                    Include:
                    - "AE"
                Check:
                  expression: >-
                    true
                """;
        Analysis a = analyser.analyse(corpus(), "rules-src/checks/CDISC/CDISC-CG9999.yaml", parked,
                null);
        assertTrue(a.parked());
        assertFalse(a.loaded());
        assertTrue(a.diagnostics().stream().anyMatch(d -> "PARKED".equals(d.code())));
        assertFalse(
                a.diagnostics().stream()
                        .anyMatch(d -> RuleAnalyser.SENSITIVITY_UNDERIVABLE.equals(d.code())),
                a.diagnostics().toString());
        // the same Check NOT parked keeps whatever the engine says about it
        Analysis live = analyser.analyse(corpus(), "rules-src/checks/CDISC/CDISC-CG9999.yaml",
                parked.replace("Executability: \"Not Executable\"\n", ""), null);
        assertFalse(live.parked());
    }


    @Test
    void aParkedRuleStillGetsItsDerivedFieldsScopeAndAuthorities() throws IOException
    {
        // REST review #8: a parked rule had no derived fields though its author edits it
        String parked = RemoteRepo.CHECK_B_TEXT.replace("Description:",
                "Executability: \"Not Executable\"\nDescription:");
        Analysis a = analyser.analyse(corpus(), RemoteRepo.CHECK_B, parked, null);
        assertTrue(a.parked());
        assertFalse(a.loaded(), "parked: never loaded, as the gate sees it");
        assertTrue(a.diagnostics().stream().anyMatch(d -> "PARKED".equals(d.code())));
        Analysis.Derived derived = a.derived();
        assertNotNull(derived, "derived from the check read without its Executability");
        assertTrue(derived.outputVariables().effective().containsAll(List.of("AEDECOD", "AETERM")),
                derived.outputVariables().toString());
        assertNotNull(derived.authorities(), "the authorities of the parked canonical node");
        assertTrue(
                a.scope().datasets().stream().anyMatch(d -> "AE".equals(d.name()) && d.inScope()),
                "the scope universe of the check");
        assertFalse(a.scope().variables().isEmpty());
        assertFalse(a.referencedVariables().isEmpty());
        // a parked check that does not load even unparked: the authorities, nothing derived
        Analysis broken = analyser.analyse(corpus(), RemoteRepo.CHECK_B,
                parked.replace("AEDECOD != \"\" and AETERM == \"\"", "(AEDECOD =="), null);
        assertTrue(broken.parked());
        assertNotNull(broken.derived());
        assertEquals("NONE", broken.derived().sensitivityConfidence());
        assertEquals(List.of(), broken.derived().outputVariables().effective());
    }


    @Test
    void aClassVariableStandsForThePrefixedVariablesOfInScopeDatasetsOnly()
    {
        // REST review #7: by suffix and length alone, --ENDTC listed DM's RFENDTC
        assertEquals(List.of("AEENDTC"),
                VariableCatalogue.concreteOf("--ENDTC",
                        List.of("AEENDTC", "RFENDTC", "CMENDTC", "--ENDTC", "AEENDTCX"),
                        Set.of("AE", "DM")));
        assertEquals(List.of("AEENDTC", "CMENDTC"), VariableCatalogue.concreteOf("--ENDTC",
                List.of("AEENDTC", "RFENDTC", "CMENDTC"), Set.of("AE", "CM")));
    }


    @Test
    void bindingsAndDocsPathAreReadFromTheBuffer()
    {
        var check = RuleAnalyser.readCheck("""
                Core:
                  Id: "X-1"
                Bindings:
                - name: "$n"
                  expression: "record_count()"
                - name: "$m"
                  expression: "1"
                """);
        assertEquals(List.of("$n", "$m"), RuleAnalyser.bindingsOf(check));
        assertEquals(List.of(), RuleAnalyser.bindingsOf(null));
        var required = RuleAnalyser.readCheck("""
                Core:
                  Id: "X-2"
                Requirements:
                  Variables:
                    All:
                    - "TSPARMCD"
                    - "TSVAL"
                    Any:
                    - "TSVAL"
                    - "TSVALCD"
                """);
        assertEquals(List.of("TSPARMCD", "TSVAL", "TSVALCD"),
                RuleAnalyser.requiredVariablesOf(required));
        assertEquals(List.of(), RuleAnalyser.requiredVariablesOf(null));
        assertEquals(List.of(), RuleAnalyser.requiredVariablesOf(check));
        assertEquals("rules-src/docs/FDA/FDA-SD1142.yaml",
                RuleAnalyser.docsPathOf("rules-src/checks/FDA/FDA-SD1142.yaml"));
        assertNull(RuleAnalyser.docsPathOf("rules-src/docs/FDA/FDA-SD1142.yaml"));
        assertNull(RuleAnalyser.docsPathOf("rules-src/checks/FDA/sub/FDA-SD1142.yaml"));
        assertNull(RuleAnalyser.readCheck("- a list, not a mapping\n"));
        assertNull(RuleAnalyser.readCheck("[broken"));
    }


    @Test
    void withoutAStoreTheScopeIsEmptyAndTheFlagSaysSo() throws IOException
    {
        try (EngineStore none = new EngineStore((java.nio.file.Path) null))
        {
            Analysis a = new RuleAnalyser(none, standards).analyse(corpus(), RemoteRepo.CHECK_B,
                    RemoteRepo.CHECK_B_TEXT, null);
            assertTrue(a.loaded());
            assertFalse(a.storeAvailable());
            assertEquals(List.of(TestStore.IG_KEY), a.scope().products());
            assertEquals(List.of(), a.scope().datasets());
            assertEquals(List.of(), a.scope().variables());
        }
    }
}
