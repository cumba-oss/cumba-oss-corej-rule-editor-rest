package net.cumba.corej.editor.run;

import java.util.List;
import java.util.Map;
import net.cumba.corej.editor.engine.Analysis;
import org.jspecify.annotations.Nullable;

/**
 * The judged run as the Results and JSON tabs show it (PLAN-rule-editor §5.7): the engine's verdict
 * ({@code ScenarioRunner.ScenarioRunResult}, flattened), one row per finding, the product's v2
 * report JSON for that one run, and the expectations the observed findings propose ("take actual as
 * expected", written only on confirmation).
 *
 * @param outcome
 *            {@code PASS} | {@code FAIL} | {@code ABORTED}
 * @param code
 *            {@code OK} or the engine's mapping-table code ({@code SCOPE}, {@code VERDICT},
 *            {@code LOCATION}, {@code PARSE}, {@code SIDECAR}, …); the editor adds
 *            {@code RULE_ERRORS}, {@code PARKED}, {@code TIMEOUT}, {@code WORKER_FAILED}
 * @param detail
 *            the message
 * @param scopeGate
 *            the pre-gate
 * @param evaluation
 *            the folded engine outcome, or {@code null} before execution
 * @param skip
 *            why the rule did not execute, or {@code null}
 * @param verdict
 *            the execution-state check, or {@code null} when not reached
 * @param locations
 *            the location check, or {@code null} when not reached
 * @param results
 *            the engine's results, one per concrete rule
 * @param findings
 *            every violation across the results
 * @param reportJson
 *            the v2 report document, or {@code null} when nothing executed
 * @param expectations
 *            the proposed {@code #expectViolation*} lines, or {@code null}
 * @param ruleDiagnostics
 *            the assembler's diagnostics when the rule could not be run ({@code RULE_ERRORS})
 * @param durationMs
 *            wall-clock of the run, worker start included
 * @param worker
 *            {@code child} (a worker JVM) or {@code inline} (the executor in-process — tests)
 */
public record RunReport(String outcome, String code, String detail, ScopeGate scopeGate,
        @Nullable Evaluation evaluation, @Nullable Skip skip, @Nullable Check verdict,
        @Nullable Check locations, List<Result> results, List<Finding> findings,
        @Nullable String reportJson, @Nullable Expectations expectations,
        List<Analysis.Diagnostic> ruleDiagnostics, long durationMs, String worker)
{

    public RunReport
    {
        results = List.copyOf(results);
        findings = List.copyOf(findings);
        ruleDiagnostics = List.copyOf(ruleDiagnostics);
    }

    public record ScopeGate(boolean inScope, @Nullable String mismatch)
    {
    }


    public record Evaluation(boolean violated, boolean executed, boolean errored, long totalCount,
            boolean truncated, @Nullable String notExecutedReason)
    {
    }


    public record Skip(String kind, String text)
    {
    }


    public record Check(boolean pass, String detail)
    {
    }


    /** One engine result (one concrete rule after expansion). */
    public record Result(@Nullable String ruleId, String status, @Nullable String statusMessage,
            @Nullable String message, long violationCount, long totalViolationCount,
            boolean truncated, long runtimeMillis)
    {
    }


    /** One finding row. */
    public record Finding(@Nullable String ruleId, long row, @Nullable String usubjid,
            @Nullable String seq, Map<String, String> keys, Map<String, String> values,
            @Nullable String level, String unit, Map<String, String> groupKey,
            List<String> namedColumns, @Nullable String message)
    {

        public Finding
        {
            keys = Map.copyOf(keys);
            values = Map.copyOf(values);
            groupKey = Map.copyOf(groupKey);
            namedColumns = List.copyOf(namedColumns);
        }
    }


    /** The proposed expectation directives. */
    public record Expectations(@Nullable Integer count, int ats, List<String> lines)
    {

        public Expectations
        {
            lines = List.copyOf(lines);
        }
    }

    /** A run that stopped in the editor before (or instead of) the engine. */
    public static RunReport stopped(String aOutcome, String aCode, String aDetail,
            List<Analysis.Diagnostic> aDiagnostics, long aDurationMs, String aWorker)
    {
        return new RunReport(aOutcome, aCode, aDetail, new ScopeGate(true, null), null, null, null,
                null, List.of(), List.of(), null, null, aDiagnostics, aDurationMs, aWorker);
    }


    public RunReport withTiming(long aDurationMs, String aWorker)
    {
        return new RunReport(outcome, code, detail, scopeGate, evaluation, skip, verdict, locations,
                results, findings, reportJson, expectations, ruleDiagnostics, aDurationMs, aWorker);
    }
}
