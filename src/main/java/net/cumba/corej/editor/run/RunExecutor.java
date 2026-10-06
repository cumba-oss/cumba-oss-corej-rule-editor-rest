package net.cumba.corej.editor.run;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.authoring.AssembledRule;
import net.cumba.corej.authoring.AuthoredRuleAssembler;
import net.cumba.corej.authoring.RuleDiagnostic;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.metadata.store.StoreFormatException;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.StandardRef;
import net.cumba.corej.core.report.ReportSections;
import net.cumba.corej.core.report.json.JsonReportWriter;
import net.cumba.corej.editor.engine.Analysis;
import net.cumba.corej.editor.engine.CdtService;
import net.cumba.corej.editor.engine.EngineStore;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestCdt;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario.Verdict;
import net.cumba.corej.ruletest.cdt.ruletest.RunEnvironment;
import net.cumba.corej.ruletest.cdt.ruletest.RunEnvironment.StoreAvailability;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioReports;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner.ScenarioRunResult;
import net.cumba.corej.ruletest.cdt.ruletest.SidecarResolver;
import net.cumba.corej.ruletest.cdt.ruletest.ViolationLocationCheck;
import net.cumba.corej.ruletest.cdt.ruletest.ViolationLocationCheck.Expectations;
import org.jspecify.annotations.Nullable;

/**
 * One rule × one scenario, judged by the engine (PLAN-rule-editor §5.7, O8): the buffer is
 * assembled through {@code AuthoredRuleAssembler}, the scenario text runs through
 * {@code ScenarioRunner.runText} with the host's store, dictionaries and the confined
 * {@code SidecarResolver} — the corpus gate's own code, so the verdict <b>is</b> the gate's. The
 * report JSON is the product's v2 document for that one run ({@code ScenarioReports} +
 * {@code JsonReportWriter}); the proposed expectations come from
 * {@code ViolationLocationCheck.toExpectations}, rendered by {@code RuleTestCdt}'s own writer, with
 * the {@code #test} line's {@code expect=} the proposal implies (REST review #2).
 *
 * <p>
 * Runs in the worker JVM ({@link RunWorker}); in-process only from tests. Never throws for what the
 * run raises: every failure is a report with a code.
 * </p>
 */
public final class RunExecutor
{

    private static final System.Logger LOG = System.getLogger(RunExecutor.class.getName());

    static final String RULE_ERRORS = "RULE_ERRORS";

    static final String PARKED = "PARKED";

    private RunExecutor()
    {
    }


    public static RunReport execute(RunJob aJob)
    {
        long t0 = System.nanoTime();
        List<StandardRef> standards = new ArrayList<>();
        for (RunJob.StandardRefJson s : aJob.packageStandards())
        {
            standards.add(new StandardRef(s.id(), StandardRef.Role.fromWire(s.role())));
        }
        AssembledRule assembled = AuthoredRuleAssembler.assemble(aJob.checkYaml(), aJob.docYaml(),
                standards);
        List<Analysis.Diagnostic> diagnostics = new ArrayList<>();
        for (RuleDiagnostic d : assembled.diagnostics())
        {
            diagnostics.add(toDiagnostic(d));
        }
        if (assembled.parked())
        {
            return RunReport.stopped("FAIL", PARKED,
                    "the rule is parked (Executability \"Not Executable\"): nothing to run",
                    diagnostics, millis(t0), "inline");
        }
        Rule rule = assembled.rule();
        if (rule == null || assembled.hasErrors())
        {
            return RunReport.stopped("FAIL", RULE_ERRORS,
                    "the rule does not load: " + assembled.errors().size()
                            + " error(s) — see the Problems tab",
                    diagnostics, millis(t0), "inline");
        }
        CdtService.Parsed parsed = CdtService.parse(aJob.cdtText());
        if (parsed.error() != null)
        {
            // The runner maps the directive parser's failures to PARSE; a dataset block the
            // table parser refuses would surface as INTERNAL — judged here first, same code.
            return RunReport.stopped("FAIL", "PARSE", parsed.error(), diagnostics, millis(t0),
                    "inline");
        }
        Path scenarioFile = Path.of(aJob.scenarioFile());
        SidecarResolver sidecars;
        try
        {
            sidecars = SidecarResolver.rootedAt(Path.of(aJob.sidecarRoot()));
        }
        catch (IOException e)
        {
            return RunReport.stopped("FAIL", "SIDECAR",
                    "the sidecar tree cannot be opened: " + e.getMessage(), diagnostics, millis(t0),
                    "inline");
        }
        RunEnvironment env = new RunEnvironment(store(aJob.storePath()),
                aJob.dictionariesDir() == null ? null : Path.of(aJob.dictionariesDir()), sidecars);
        ScenarioRunResult result = ScenarioRunner.runText(rule, aJob.cdtText(), scenarioFile, env,
                null);
        RuleTestScenario scenario = parseQuietly(aJob.cdtText(), sidecars, scenarioFile);
        return toReport(rule, scenario, aJob.cdtText(), result, aJob.engineVersion(), diagnostics,
                millis(t0));
    }


    static StoreAvailability store(@Nullable String aPath)
    {
        String origin = "the configured metadata store";
        if (aPath == null || aPath.isBlank())
        {
            return StoreAvailability.unavailable(origin,
                    "no metadata store is configured on this instance", false);
        }
        // the reason reaches the client: the file name, never the server's path (security
        // review L8)
        Path path = Path.of(aPath);
        if (!Files.isRegularFile(path))
        {
            return StoreAvailability.unavailable(origin,
                    "the configured metadata store file does not exist", false);
        }
        try
        {
            return StoreAvailability.of(StoreMetadataProviderFactory.open(path), origin);
        }
        catch (StoreFormatException e)
        {
            return StoreAvailability.unavailable(origin,
                    EngineStore.withoutDirectory(e.describe(), path), true);
        }
        catch (IOException e)
        {
            LOG.log(System.Logger.Level.WARNING, () -> "metadata store " + path + " unreadable", e);
            return StoreAvailability.unavailable(origin,
                    "the configured metadata store is unreadable", false);
        }
    }


    private static @Nullable RuleTestScenario parseQuietly(String aText, SidecarResolver aSidecars,
            Path aScenarioFile)
    {
        try
        {
            return RuleTestCdt.parse(aText, aSidecars.label(aScenarioFile),
                    aSidecars.includeResolver(aScenarioFile));
        }
        catch (RuntimeException e)
        {
            // RuleTestCdtException included: a scenario that does not parse has no report JSON
            return null;
        }
    }


    static RunReport toReport(Rule aRule, @Nullable RuleTestScenario aScenario, String aCdtText,
            ScenarioRunResult aResult, @Nullable String aEngineVersion,
            List<Analysis.Diagnostic> aDiagnostics, long aDurationMs)
    {
        List<RunReport.Result> results = new ArrayList<>();
        List<RunReport.Finding> findings = new ArrayList<>();
        for (RuleExecutionResult r : aResult.results())
        {
            results.add(new RunReport.Result(r.getRuleId(), r.getStatus().name(),
                    r.getStatusMessage(), r.getMessage(), r.getViolationCount(),
                    r.getTotalViolationCount(), r.isTruncated(), r.getRuntimeMillis()));
            for (Violation v : r.getViolations())
            {
                findings.add(toFinding(r, v));
            }
        }
        ScenarioRunner.EvalOutcome eval = aResult.evaluation();
        RunReport.Evaluation evaluation = eval == null ? null
                : new RunReport.Evaluation(eval.violated(), eval.executed(), eval.errored(),
                        eval.totalCount(), eval.truncated(), eval.notExecutedReason());
        String reportJson = null;
        RunReport.Expectations expectations = null;
        if (aScenario != null && !aResult.results().isEmpty())
        {
            reportJson = reportJson(aRule, aScenario, aResult.results(), aEngineVersion);
        }
        if (aScenario != null && eval != null)
        {
            expectations = expectations(aRule, aScenario, aCdtText, eval);
        }
        return new RunReport(aResult.outcome().name(), aResult.code(), aResult.detail(),
                new RunReport.ScopeGate(aResult.scopeGate().inScope(),
                        aResult.scopeGate().mismatch()),
                evaluation,
                aResult.skip() == null ? null
                        : new RunReport.Skip(aResult.skip().kind().name(), aResult.skip().text()),
                aResult.verdict() == null ? null
                        : new RunReport.Check(aResult.verdict().pass(), aResult.verdict().detail()),
                aResult.locations() == null ? null
                        : new RunReport.Check(aResult.locations().pass(),
                                aResult.locations().detail()),
                results, findings, reportJson, expectations, aDiagnostics, aDurationMs, "inline");
    }


    static RunReport.Finding toFinding(RuleExecutionResult aResult, Violation aViolation)
    {
        return new RunReport.Finding(aResult.getRuleId(), aViolation.getRowNumber(),
                aViolation.getUsubjid(), aViolation.getSeq(),
                aViolation.getKeys() == null ? Map.of() : aViolation.getKeys(),
                aViolation.getValues() == null ? Map.of() : aViolation.getValues(),
                aViolation.getLevel() == null ? null : aViolation.getLevel().name(),
                String.valueOf(aViolation.getUnit()),
                aViolation.getGroupKey() == null ? Map.of() : aViolation.getGroupKey(),
                aViolation.getNamedColumns() == null ? List.of() : aViolation.getNamedColumns(),
                aResult.getMessage());
    }


    static @Nullable String reportJson(Rule aRule, RuleTestScenario aScenario,
            List<RuleExecutionResult> aResults, @Nullable String aEngineVersion)
    {
        try
        {
            ReportSections sections = ScenarioReports.sections(aRule, aScenario, aResults,
                    aEngineVersion);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new JsonReportWriter(true).write(sections, out);
            return out.toString(StandardCharsets.UTF_8);
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }


    /**
     * What "take actual as expected" proposes (§5.6, REST review #2), or {@code null}:
     * <ul>
     * <li>the rule fired: {@code expect=violation} and the {@code #expectViolation*} lines the
     * observed findings imply, rendered through {@code RuleTestCdt}'s own writer so quoting and
     * ordering match a hand-written file exactly (as the corpus back-fill does);</li>
     * <li>it executed cleanly and fired nothing on a scenario that expects a violation:
     * {@code expect=noViolation} with the expectations cleared;</li>
     * <li>otherwise nothing — a skipped or errored run proposes no verdict.</li>
     * </ul>
     * The proposal carries the rewritten {@code #test} line, so the confirm diff shows the verdict
     * change too, and {@code POST /api/cdt/expectations} with the same {@code expect} writes a text
     * the parser accepts (the old proposal kept {@code expect=noViolation} beside
     * {@code #expectViolationAt} lines, a buffer the parser refuses).
     */
    static RunReport.@Nullable Expectations expectations(Rule aRule, RuleTestScenario aScenario,
            String aCdtText, ScenarioRunner.EvalOutcome aEval)
    {
        try
        {
            if (aEval.violated())
            {
                boolean valueBased = aRule.getEvaluationDomain() == null
                        || aRule.getEvaluationDomain().rowCursor();
                Expectations e = ViolationLocationCheck.toExpectations(aEval.violations(),
                        aEval.totalCount(), aEval.truncated(), valueBased, aScenario.getDomain());
                RuleTestScenario tmp = aScenario.toBuilder().expect(Verdict.VIOLATION)
                        .expectErrorMessage(null).expectAbort(null).expectViolationCount(e.count())
                        .clearExpectedViolations().expectedViolations(e.ats()).build();
                List<String> lines = new ArrayList<>();
                for (String line : RuleTestCdt.toString(tmp).split("\n", -1))
                {
                    if (line.startsWith("#expectViolation"))
                    {
                        lines.add(line);
                    }
                }
                String token = Verdict.VIOLATION.token();
                return new RunReport.Expectations(e.count(), e.ats().size(), lines, token,
                        CdtService.testLineWith(aCdtText, token));
            }
            if (aEval.executed() && !aEval.errored() && aScenario.getExpect() == Verdict.VIOLATION)
            {
                String token = Verdict.NO_VIOLATION.token();
                return new RunReport.Expectations(null, 0, List.of(), token,
                        CdtService.testLineWith(aCdtText, token));
            }
            return null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }


    static Analysis.Diagnostic toDiagnostic(RuleDiagnostic aDiagnostic)
    {
        RuleDiagnostic.TextRange r = aDiagnostic.range();
        return new Analysis.Diagnostic(aDiagnostic.code(), aDiagnostic.severity().name(),
                aDiagnostic.message(),
                aDiagnostic.path() == null ? null : aDiagnostic.path().toString(),
                r == null ? null
                        : new Analysis.Range(r.startLine(), r.startColumn(), r.endLine(),
                                r.endColumn()),
                aDiagnostic.document().name());
    }


    private static long millis(long aStartNanos)
    {
        return (System.nanoTime() - aStartNanos) / 1_000_000;
    }
}
