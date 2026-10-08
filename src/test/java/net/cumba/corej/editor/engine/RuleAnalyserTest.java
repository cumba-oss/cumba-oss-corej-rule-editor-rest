package net.cumba.corej.editor.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.cumba.corej.authoring.AuthoredRuleAssembler;
import net.cumba.corej.core.metadata.store.MetadataStore;
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
        // ... yet its universe is still the Scope's (owner 2026-10-08)
        assertEquals(a.scope(), broken.scope());
    }


    /** CHECK_B with its expression replaced: what the author has typed so far. */
    private static String withExpression(String aText, String aExpression)
    {
        String text = aText.replace("AEDECOD != \"\" and AETERM == \"\"", aExpression);
        assertNotEquals(aText, text, "the fixture's expression was replaced");
        return text;
    }


    @Test
    void anUnparsableCheckKeepsTheScopeUniverseOfItsScope() throws IOException
    {
        // owner 2026-10-08: typing `empty(` made every variable vanish from completion
        Analysis valid = analyser.analyse(corpus(), RemoteRepo.CHECK_B, RemoteRepo.CHECK_B_TEXT,
                null);
        Analysis typing = analyser.analyse(corpus(), RemoteRepo.CHECK_B,
                withExpression(RemoteRepo.CHECK_B_TEXT, "empty("), null);
        assertTrue(valid.loaded());
        assertFalse(typing.loaded());
        // the diagnostics still describe the real buffer: the expression error, ranged
        assertTrue(
                typing.diagnostics().stream()
                        .anyMatch(d -> AuthoredRuleAssembler.EXPRESSION_SYNTAX.equals(d.code())
                                && "ERROR".equals(d.severity()) && d.range() != null),
                typing.diagnostics().toString());
        assertNull(typing.derived(), "nothing is derived from a check that does not load");
        assertEquals(List.of(), typing.referencedVariables());
        // ... but the universe is the Scope's, identical to the loaded rule's
        assertFalse(valid.scope().variables().isEmpty());
        assertEquals(valid.scope(), typing.scope());
        assertTrue(typing.storeAvailable());
    }


    @Test
    void theScopeOnlyUniverseIsJudgedByTheAuthoredScope() throws IOException
    {
        // sensitivity control: the scope-only reading honours the Scope (not "no Scope = all")
        String ts = RemoteRepo.CHECK_B_TEXT.replace("    - \"AE\"\n", "    - \"TS\"\n");
        assertNotEquals(RemoteRepo.CHECK_B_TEXT, ts);
        Analysis valid = analyser.analyse(corpus(), RemoteRepo.CHECK_B, ts, null);
        Analysis typing = analyser.analyse(corpus(), RemoteRepo.CHECK_B,
                withExpression(ts, "not empty(TSVAL) and"), null);
        assertTrue(valid.loaded());
        assertFalse(typing.loaded());
        assertEquals(valid.scope(), typing.scope());
        Set<String> inScope = typing.scope().datasets().stream()
                .filter(Analysis.ScopeDataset::inScope).map(Analysis.ScopeDataset::name)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("TS"), inScope);
        Set<String> names = typing.scope().variables().stream().map(Analysis.Variable::name)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(names.contains("TSVAL") && !names.contains("AETERM"), names.toString());
    }


    @Test
    void anInvalidScopeLeavesTheUniverseEmptyWithoutAnException() throws IOException
    {
        String badScope = withExpression(RemoteRepo.CHECK_B_TEXT, "empty(")
                .replace("  Domains:\n    Include:\n    - \"AE\"\n", "  Domains: [\"AE\"]\n");
        assertFalse(badScope.contains("Include"), badScope);
        Analysis a = analyser.analyse(corpus(), RemoteRepo.CHECK_B, badScope, null);
        assertFalse(a.loaded());
        assertTrue(a.storeAvailable());
        assertEquals(List.of(TestStore.IG_KEY), a.scope().products(), "products from Standards");
        assertEquals(List.of(), a.scope().datasets());
        assertEquals(List.of(), a.scope().variables());
        assertTrue(a.diagnostics().stream().anyMatch(d -> "ERROR".equals(d.severity())),
                a.diagnostics().toString());
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


    /** CHECK_B (an AE rule) with {@code aMatchDatasets} as its {@code Match_Datasets} block. */
    private static String withJoins(String aText, String aMatchDatasets)
    {
        String text = aText.replace("Check:\n", "Match_Datasets:\n" + aMatchDatasets + "Check:\n");
        assertNotEquals(aText, text, "the fixture's Check was found");
        return text;
    }

    private static final String JOIN_DM = """
            - Name: "DM"
              Keys: ["STUDYID", "USUBJID"]
            """;

    private static Set<String> names(List<Analysis.Variable> aVariables)
    {
        return aVariables.stream().map(Analysis.Variable::name)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }


    @Test
    void aKeyedJoinListsTheJoinedDatasetWithItsVariables() throws IOException
    {
        // owner 2026-10-08: DM.RFSTDTC on an AE rule had no completion
        Analysis plain = analyser.analyse(corpus(), RemoteRepo.CHECK_B, RemoteRepo.CHECK_B_TEXT,
                null);
        Analysis a = analyser.analyse(corpus(), RemoteRepo.CHECK_B,
                withJoins(RemoteRepo.CHECK_B_TEXT, JOIN_DM), null);
        assertTrue(a.loaded(), a.diagnostics().toString());
        assertEquals(List.of(), plain.joined());
        assertEquals(List.of("DM"), a.joined().stream().map(Analysis.Joined::name).toList());
        Analysis.Joined dm = a.joined().get(0);
        Set<String> names = names(dm.variables());
        assertTrue(names.containsAll(List.of("STUDYID", "USUBJID", "RFSTDTC", "SEX")),
                names.toString());
        // exactly DM: the primary's and the other datasets' variables are not DM's
        assertFalse(names.contains("AETERM") || names.contains("TSVAL"), names.toString());
        Analysis.Variable rfstdtc = dm.variables().stream().filter(v -> "RFSTDTC".equals(v.name()))
                .findFirst().orElseThrow();
        assertEquals("Subject Reference Start Date/Time", rfstdtc.label());
        assertEquals(List.of("DM"), rfstdtc.datasets());
        assertEquals(List.of(TestStore.IG_KEY), rfstdtc.products());
        // the primary universe is the Scope's, untouched by the join
        assertEquals(plain.scope(), a.scope());
    }


    @Test
    void aJoinedDatasetListsNoClassLevelTemplate() throws IOException
    {
        // review H1: the engine resolves `AE.--TERM` with the PRIMARY's prefix (a DM rule reads
        // AE.DMTERM), so a joined dataset offers its concrete names only — never a `--` entry
        String dmRule = withJoins(
                withExpression(RemoteRepo.CHECK_B_TEXT.replace("    - \"AE\"\n", "    - \"DM\"\n"),
                        "AE.AETERM == \"\""),
                """
                        - Name: "AE"
                          Keys: ["STUDYID", "USUBJID"]
                        """);
        Analysis a = analyser.analyse(corpus(), RemoteRepo.CHECK_B, dmRule, null);
        assertTrue(a.loaded(), a.diagnostics().toString());
        assertEquals(List.of("AE"), a.joined().stream().map(Analysis.Joined::name).toList());
        Set<String> names = names(a.joined().get(0).variables());
        assertTrue(names.containsAll(List.of("AETERM", "AEDECOD", "AESTDTC")), names.toString());
        assertTrue(names.stream().noneMatch(n -> n.startsWith("--")), names.toString());
        assertTrue(a.joined().get(0).variables().stream().noneMatch(Analysis.Variable::classLevel));
    }


    @Test
    void anAdamJoinOfAdslListsTheAdslStructuresVariablesOnly() throws IOException
    {
        String adam = """
                Core:
                  Id: "CDISC-AD9999"
                  Status: "Draft"
                Description: "BDS rule reading ADSL."
                Scope:
                  Classes:
                    Include:
                    - "BASIC DATA STRUCTURE"
                Match_Datasets:
                - Name: "ADSL"
                  Keys: ["STUDYID", "USUBJID"]
                Check:
                  expression: >-
                    ADSL.TRT01P == ""
                Standards:
                - Organization: "CDISC"
                  Standards:
                  - Name: "ADaMIG"
                    Version: "1.3"
                    References:
                    - Origin: "ADaM Conformance Rules"
                      Rule_Identifier:
                        Id: "AD9999"
                        Version: "1"
                      Version: "5.0"
                """;
        Analysis a = analyser.analyse(corpus(), "rules-src/checks/CDISC/CDISC-AD9999.yaml", adam,
                null);
        assertEquals(List.of(TestStore.ADAM_KEY), a.scope().products());
        assertEquals(List.of("ADSL"), a.joined().stream().map(Analysis.Joined::name).toList());
        // the ADaM IG names structures, not datasets: the ScopeEntry of ADSL is the structure,
        // and only it is admitted — BDS's PARAMCD / AVAL are not ADSL's; the Library spells the
        // treatment variable as its template (review L4), and that template is what is offered
        assertEquals(Set.of("STUDYID", "USUBJID", "TRTxxP"), names(a.joined().get(0).variables()));
    }


    @Test
    void entriesThatJoinNoPlainDatasetAreSkipped() throws IOException
    {
        String joins = """
                - Name: "TS"
                  Keys: ["STUDYID"]
                - Name: "AE"
                  Child: true
                - Name: "AE"
                  Child: true
                  Keys: ["STUDYID", "USUBJID"]
                - Name: "SUPP--"
                  Keys: ["STUDYID", "USUBJID"]
                - Name: "TV"
                - Name: "RELREC"
                - Name: "RELREC"
                  Keys: ["STUDYID", "USUBJID"]
                - Name: "&DOM&"
                  Keys: ["STUDYID", "USUBJID"]
                - Name: "Dm"
                - Keys: ["STUDYID", "USUBJID"]
                - Name: "DM"
                  Keys: ["STUDYID", "USUBJID"]
                - Name: "dm"
                  Keys: ["USUBJID"]
                """;
        Analysis a = analyser.analyse(corpus(), RemoteRepo.CHECK_B,
                withJoins(RemoteRepo.CHECK_B_TEXT, joins), null);
        // authored order; one per name (case-insensitive, first spelling); a keyless TV is not
        // listed, and a keyless "Dm" does not count — the later keyed "DM" is the listed spelling
        // (review L1: without the keyless skip this would read [TS, TV, Dm])
        assertEquals(List.of("TS", "DM"), a.joined().stream().map(Analysis.Joined::name).toList());
        assertTrue(names(a.joined().get(0).variables()).contains("TSVAL"));
        assertTrue(names(a.joined().get(1).variables()).contains("RFSTDTC"));
        // sensitivity control: the same entry not marked Child is joined
        Analysis notChild = analyser.analyse(corpus(), RemoteRepo.CHECK_B,
                withJoins(RemoteRepo.CHECK_B_TEXT, """
                        - Name: "AE"
                          Keys: ["STUDYID", "USUBJID", "AESEQ"]
                        """), null);
        assertEquals(List.of("AE"), notChild.joined().stream().map(Analysis.Joined::name).toList());
    }


    @Test
    void theJoinTypeIsTheEnginesWithInnerAsTheDefault() throws IOException
    {
        String joins = """
                - Name: "DM"
                  Keys: ["STUDYID", "USUBJID"]
                - Name: "TS"
                  Keys: ["STUDYID"]
                  Join_Type: "LEFT"
                - Name: "SUPPAE"
                  Keys: ["STUDYID", "USUBJID"]
                  Join_Type: "left"
                - Name: "DM"
                  Keys: ["STUDYID", "USUBJID"]
                  Join_Type: "left"
                """;
        Analysis a = analyser.analyse(corpus(), RemoteRepo.CHECK_B,
                withJoins(RemoteRepo.CHECK_B_TEXT, joins), null);
        // absent = the stamped default; the engine's vocabulary ignores case; the first entry of
        // a name carries its join type
        assertEquals(List.of("DM:inner", "TS:left", "SUPPAE:left"),
                a.joined().stream().map(j -> j.name() + ":" + j.joinType()).toList());
        // an unrecognised value is passed through lower-cased — never read as left — and the
        // rule does not load
        Analysis typo = analyser.analyse(corpus(), RemoteRepo.CHECK_B,
                withJoins(RemoteRepo.CHECK_B_TEXT, """
                        - Name: "DM"
                          Keys: ["STUDYID", "USUBJID"]
                          Join_Type: "Iner"
                        """), null);
        assertTrue(errors(typo).stream().anyMatch(m -> m.contains("Iner")), typo.toString());
        assertEquals("iner", typo.joined().get(0).joinType());
    }


    /** The messages of the analysis's ERROR diagnostics. */
    private static List<String> errors(Analysis aAnalysis)
    {
        return aAnalysis.diagnostics().stream().filter(d -> "ERROR".equals(d.severity()))
                .map(Analysis.Diagnostic::message).toList();
    }


    @Test
    void theMatchedFlagIsAcceptedExactlyWhenTheJoinTypeIsLeft() throws IOException
    {
        // the web offers <Name>._matched_ only on joinType "left": the engine agrees, for an
        // ordinary entry and for a supplemental family (which keeps every row, yet its absent
        // Join_Type is still stamped inner and the flag refused); the keys are declared
        // (joins.md "Declare the key"), so the flag is the only thing that can be refused
        for (String name : List.of("DM", "SUPPAE"))
        {
            for (String declared : List.of("", "  Join_Type: \"inner\"\n",
                    "  Join_Type: \"left\"\n"))
            {
                String block = "Requirements:\n  Variables:\n    All: [\"STUDYID\"]\n"
                        + "    All_Or_None:\n    - [\"STUDYID\", \"" + name + ".STUDYID\"]\n"
                        + "    - [\"USUBJID\", \"" + name + ".USUBJID\"]\n"
                        + "Match_Datasets:\n- Name: \"" + name + "\"\n"
                        + "  Keys: [\"STUDYID\", \"USUBJID\"]\n" + declared + "Check:\n";
                String text = withExpression(RemoteRepo.CHECK_B_TEXT, "not " + name + "._matched_")
                        .replace("Check:\n", block);
                Analysis a = analyser.analyse(corpus(), RemoteRepo.CHECK_B, text, null);
                String joinType = a.joined().get(0).joinType();
                List<String> errors = errors(a);
                String label = name + " " + joinType + " " + errors;
                assertEquals("left".equals(joinType), errors.isEmpty(), label);
                if (declared.isEmpty())
                {
                    assertEquals("inner", joinType);
                    assertTrue(errors.stream().anyMatch(m -> m.contains("with an inner join")),
                            label);
                }
            }
        }
    }


    @Test
    @SuppressWarnings("PMD.CloseResource") // the store is shared, owned and closed by EngineStore
    void aKeyedSupplementalFamilyListsTheSuppqualVariables() throws IOException
    {
        // joins.md: a keyed SUPPAE is a partner whose columns are the raw supplemental columns
        // (SUPPAE.QNAM); the store declares them once, as SUPPQUAL
        Analysis a = analyser.analyse(corpus(), RemoteRepo.CHECK_B,
                withJoins(withExpression(RemoteRepo.CHECK_B_TEXT, "SUPPAE.QNAM == \"AETRTEM\""), """
                        - Name: "SUPPAE"
                          Keys: ["STUDYID", "USUBJID"]
                          Join_Type: "left"
                        - Name: "SQAPAE"
                          Keys: ["STUDYID", "USUBJID"]
                          Join_Type: "left"
                        """), null);
        assertTrue(a.loaded(), a.diagnostics().toString());
        assertEquals(List.of("SUPPAE", "SQAPAE"),
                a.joined().stream().map(Analysis.Joined::name).toList());
        for (Analysis.Joined j : a.joined())
        {
            assertEquals(Set.of("STUDYID", "RDOMAIN", "USUBJID", "QNAM", "QVAL"),
                    names(j.variables()), j.name());
        }
        assertEquals(List.of("SUPPQUAL"), a.joined().get(0).variables().get(0).datasets());
        // sensitivity control: the store name is what serves them — SUPPAE itself names nothing
        MetadataStore open = store.store().orElseThrow();
        assertEquals(List.of(),
                analyser.joinedVariables("SUPPAE", open, 1L, List.of(TestStore.IG_KEY)));
        assertEquals("SUPPQUAL", RuleAnalyser.storeNameOf("suppae"));
        assertEquals("SUPPQUAL", RuleAnalyser.storeNameOf("SQAPAE"));
        assertEquals("DM", RuleAnalyser.storeNameOf("DM"));
    }


    @Test
    void aNameTheStoreDoesNotDeclareIsListedWithoutVariables() throws IOException
    {
        // review L3: an SDTM split member is not mapped to its domain by name (the engine derives
        // a split's base from the DOMAIN data, ScopeMatcher), nor is an ADaM dataset
        Analysis a = analyser.analyse(corpus(), RemoteRepo.CHECK_B,
                withJoins(RemoteRepo.CHECK_B_TEXT, """
                        - Name: "DM1"
                          Keys: ["STUDYID", "USUBJID"]
                        - Name: "ADAE"
                          Keys: ["STUDYID", "USUBJID"]
                        """), null);
        assertEquals(List.of(new Analysis.Joined("DM1", "inner", List.of()),
                new Analysis.Joined("ADAE", "inner", List.of())), a.joined());
    }


    @Test
    void anUnloadableCheckStillListsItsJoins() throws IOException
    {
        Analysis valid = analyser.analyse(corpus(), RemoteRepo.CHECK_B,
                withJoins(RemoteRepo.CHECK_B_TEXT, JOIN_DM), null);
        Analysis typing = analyser.analyse(corpus(), RemoteRepo.CHECK_B,
                withJoins(withExpression(RemoteRepo.CHECK_B_TEXT, "DM.RFSTDTC == empty("), JOIN_DM),
                null);
        assertFalse(typing.loaded());
        assertEquals(valid.joined(), typing.joined());
        assertTrue(names(typing.joined().get(0).variables()).contains("RFSTDTC"));
        // a YAML that is not a mapping: nothing to join, no exception
        Analysis broken = analyser.analyse(corpus(), RemoteRepo.CHECK_B, "[broken", null);
        assertEquals(List.of(), broken.joined());
    }


    @Test
    @SuppressWarnings("PMD.CloseResource") // the store is shared, owned and closed by EngineStore
    void theJoinedVariablesAreCachedPerProductsNameAndGeneration()
    {
        MetadataStore open = store.store().orElseThrow();
        List<String> products = List.of(TestStore.IG_KEY);
        List<Analysis.Variable> first = analyser.joinedVariables("DM", open, 7L, products);
        assertFalse(first.isEmpty());
        assertSame(first, analyser.joinedVariables("dm", open, 7L, products), "cached by name");
        List<Analysis.Variable> regenerated = analyser.joinedVariables("DM", open, 8L, products);
        assertNotSame(first, regenerated, "a new store generation recomputes");
        assertEquals(first, regenerated);
        assertEquals(List.of(), analyser.joinedVariables("DM", null, 7L, products));
        assertEquals(List.of(), analyser.joinedVariables("DM", open, 7L, List.of()));
        assertNotNull(RuleAnalyser.joinedRule("ADSL"), "the minimal rule loads");
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
            // the joins are still listed, without variables (never an exception)
            Analysis joined = new RuleAnalyser(none, standards).analyse(corpus(),
                    RemoteRepo.CHECK_B, withJoins(RemoteRepo.CHECK_B_TEXT, JOIN_DM), null);
            assertEquals(List.of(new Analysis.Joined("DM", "inner", List.of())), joined.joined());
        }
    }
}
