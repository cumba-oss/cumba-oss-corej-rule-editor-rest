package net.cumba.corej.editor.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.cumba.corej.authoring.CorpusIndex;
import net.cumba.corej.authoring.RuleFamily;
import net.cumba.corej.authoring.RuleInsight;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.StoredCodelist;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredTerm;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.corpus.CorpusAnalysisIndex;
import net.cumba.corej.editor.corpus.CorpusLayout;
import net.cumba.corej.editor.engine.CdtService;
import net.cumba.corej.editor.engine.CorpusText;
import net.cumba.corej.editor.engine.EngineStore;
import net.cumba.corej.editor.engine.FunctionCatalogue;
import net.cumba.corej.editor.engine.Json;
import net.cumba.corej.editor.engine.LruCache;
import net.cumba.corej.editor.engine.RuleAnalyser;
import net.cumba.corej.editor.engine.ScenarioCatalogue;
import net.cumba.corej.editor.error.EditorException;
import net.cumba.corej.editor.error.Errors;
import net.cumba.corej.editor.git.PathPolicy;
import net.cumba.corej.editor.run.RunReport;
import net.cumba.corej.editor.run.RunService;
import net.cumba.corej.editor.session.SessionUser;
import net.cumba.corej.editor.web.dto.Dtos;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The engine behind the editor (PLAN-rule-editor phase 3): analysis of a buffer, the completion and
 * tooltip catalogues, the scenario list, related rules, the scenario grid's parse / splice /
 * expectations, and the run. Reads serve the session's working tree, else the anonymous snapshot
 * when it is on ({@link Access#read}); every buffer is size-capped ({@code 413 TOO_LARGE}, §5.8.5).
 * Nothing here writes to a workspace, and nothing slow holds its lock: a run exports under it and
 * runs outside it (security review M1), the corpus index is prepared under it and built outside it
 * (REST review #5), and the grid's text operations need no workspace at all (REST review #4).
 */
@RestController
@RequestMapping("/api")
@Tag(name = "engine", description = "Analysis, completion, scenarios, grid and runs")
public class EngineController
{

    static final String TOO_LARGE = "TOO_LARGE";

    static final int MAX_CT_TERMS = 500;

    /** Distinct {@code functions.md} texts whose catalogue is kept (REST review #11). */
    static final int MAX_FUNCTION_CATALOGUES = 8;

    private final Access access;

    private final RuleAnalyser analyser;

    private final CorpusAnalysisIndex index;

    private final EngineStore store;

    private final RunService runs;

    private final int maxBufferBytes;

    private final ClientAddress clients;

    /** Catalogue by the SHA-1 of the branch's functions.md: bounded, so branches cannot grow it. */
    private final Map<String, List<FunctionCatalogue.FunctionInfo>> functionsByText = new LruCache<>(
            MAX_FUNCTION_CATALOGUES);

    public EngineController(Access aAccess, RuleAnalyser aAnalyser, CorpusAnalysisIndex aIndex,
            EngineStore aStore, RunService aRuns, EditorProperties aProperties,
            ClientAddress aClients)
    {
        access = aAccess;
        analyser = aAnalyser;
        index = aIndex;
        store = aStore;
        runs = aRuns;
        maxBufferBytes = aProperties.getLimits().getMaxBufferBytes();
        clients = aClients;
    }

    // ---- analysis -------------------------------------------------------------------------


    @PostMapping("/analyse")
    @Operation(summary = "Analyse a rule buffer: diagnostics, derived fields, scope universe,"
            + " referenced variables (in-process, no execution)")
    public Dtos.Analysed analyse(@AuthenticationPrincipal @Nullable SessionUser aUser,
            @RequestBody Dtos.Analyse aBody, HttpServletRequest aRequest)
        throws IOException
    {
        if (aBody.path() == null || aBody.content() == null)
        {
            throw Errors.badRequest("path and content are required");
        }
        String path = PathPolicy.check(aBody.path());
        requireSize(aBody.content(), "content");
        if (aBody.docs() != null)
        {
            requireSize(aBody.docs(), "docs");
        }
        return access.read(aUser, aRequest, v -> new Dtos.Analysed(v.branch(), v.head(),
                analyser.analyse(v.source(), path, aBody.content(), aBody.docs())));
    }


    @GetMapping("/engine/functions")
    @Operation(summary = "The rule language's functions: registry signatures joined with the"
            + " branch's rule-guide/reference/functions.md help text")
    public Dtos.Functions functions(@AuthenticationPrincipal @Nullable SessionUser aUser,
            HttpServletRequest aRequest)
        throws IOException
    {
        return access.read(aUser, aRequest, v ->
        {
            String md = CorpusText.readOrEmpty(v.source(), CorpusText.FUNCTIONS_MD);
            return new Dtos.Functions(functions(md));
        });
    }


    List<FunctionCatalogue.FunctionInfo> functions(String aFunctionsMd)
    {
        String key = CorpusAnalysisIndex.sha1(aFunctionsMd.getBytes(StandardCharsets.UTF_8));
        synchronized (functionsByText)
        {
            return functionsByText.computeIfAbsent(key, k -> FunctionCatalogue.build(aFunctionsMd));
        }
    }


    /** How many function catalogues are kept right now (the bound's test). */
    int cachedFunctionCatalogues()
    {
        synchronized (functionsByText)
        {
            return functionsByText.size();
        }
    }


    @GetMapping("/engine/schema")
    @Operation(summary = "The branch's authored rule schema (rule-check.schema.json) for key and"
            + " enum completion and key tooltips; 404 NO_SCHEMA when the branch carries none,"
            + " 422 SCHEMA_INVALID when its file is not JSON")
    public Object schema(@AuthenticationPrincipal @Nullable SessionUser aUser,
            HttpServletRequest aRequest)
        throws IOException
    {
        return access.read(aUser, aRequest, v ->
        {
            Optional<String> text = CorpusText.read(v.source(), CorpusText.SCHEMA_DOCUMENTATION);
            if (text.isEmpty())
            {
                text = CorpusText.read(v.source(), CorpusText.SCHEMA_SCHEMA);
            }
            if (text.isEmpty())
            {
                throw new EditorException(HttpStatus.NOT_FOUND, "NO_SCHEMA",
                        "the branch carries no rule-check.schema.json");
            }
            try
            {
                return Json.parse(text.get());
            }
            catch (RuntimeException e)
            {
                // its own code (REST review #9): "absent" and "broken" are different situations
                EditorException notJson = new EditorException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "SCHEMA_INVALID",
                        "the branch's rule-check.schema.json is not JSON: " + e.getMessage());
                notJson.initCause(e);
                throw notJson;
            }
        });
    }


    @GetMapping("/engine/ct")
    @Operation(summary = "A codelist of the metadata store's CT, by C-code or submission value,"
            + " newest package first (tooltips); empty hits without a store")
    @SuppressWarnings("PMD.CloseResource") // the store is shared, owned and closed by EngineStore
    public Dtos.CodelistLookup codelist(@RequestParam("codelist") String aCodelist,
            @RequestParam(name = "term", required = false) @Nullable String aTerm)
    {
        String query = aCodelist.strip();
        List<Dtos.CodelistHit> hits = new ArrayList<>();
        MetadataStore open = store.store().orElse(null);
        if (open == null || query.isEmpty())
        {
            return new Dtos.CodelistLookup(query, hits);
        }
        List<String> packages = new ArrayList<>(open.publishedCtPackages());
        packages.sort((a, b) -> b.compareTo(a));
        for (String id : packages)
        {
            Optional<StoredCtPackage> pkg = open.ctPackage(id);
            if (pkg.isEmpty())
            {
                continue;
            }
            for (StoredCodelist c : pkg.get().codelists())
            {
                if (query.equalsIgnoreCase(c.conceptId())
                        || query.equalsIgnoreCase(c.submissionValue()))
                {
                    hits.add(toHit(id, c, aTerm));
                }
            }
            if (hits.size() >= 3)
            {
                break;
            }
        }
        return new Dtos.CodelistLookup(query, hits);
    }


    static Dtos.CodelistHit toHit(String aPackage, StoredCodelist aCodelist, @Nullable String aTerm)
    {
        List<Dtos.CodelistTerm> terms = new ArrayList<>();
        for (StoredTerm t : aCodelist.terms())
        {
            if (aTerm != null && !aTerm.isBlank() && !aTerm.equalsIgnoreCase(t.submissionValue())
                    && !aTerm.equalsIgnoreCase(t.conceptId()))
            {
                continue;
            }
            if (terms.size() >= MAX_CT_TERMS)
            {
                break;
            }
            terms.add(new Dtos.CodelistTerm(t.conceptId(), t.submissionValue(), t.preferredTerm(),
                    t.definition()));
        }
        return new Dtos.CodelistHit(aPackage, aCodelist.conceptId(), aCodelist.submissionValue(),
                aCodelist.name(), aCodelist.preferredTerm(), aCodelist.definition(),
                aCodelist.extensible(), aCodelist.terms().size(), terms);
    }

    // ---- scenarios and related rules ----------------------------------------------------


    @GetMapping("/scenarios")
    @Operation(summary = "The scenarios of a rule's suite directory with what their prelude"
            + " declares")
    public Dtos.Scenarios scenarios(@AuthenticationPrincipal @Nullable SessionUser aUser,
            @RequestParam("rule") String aRule, HttpServletRequest aRequest)
        throws IOException
    {
        String rule = requireRuleId(aRule);
        String family = RuleFamily.of(rule);
        return access.read(aUser, aRequest, v -> new Dtos.Scenarios(v.branch(), v.head(), rule,
                family, ScenarioCatalogue.suite(v.source(), family, rule)));
    }


    @GetMapping("/related")
    @Operation(summary = "Rules related to one: a shared authority reference across families, the"
            + " repository's equivalence / relation classes and minted parentage where it carries"
            + " the registers (§5.5)")
    public Dtos.Related related(@AuthenticationPrincipal @Nullable SessionUser aUser,
            @RequestParam("rule") String aRule, HttpServletRequest aRequest)
        throws IOException
    {
        String rule = requireRuleId(aRule);
        // the index is prepared under the workspace lock and built outside it (REST review #5)
        Prepared prepared = access.read(aUser, aRequest,
                v -> new Prepared(v.branch(), v.head(), CorpusAnalysisIndex.prepare(v)));
        CorpusAnalysisIndex.Index idx = index.of(prepared.request());
        CorpusAnalysisIndex.RuleSummary summary = idx.rules().get(rule);
        List<CorpusIndex.ReferenceKey> references = summary == null || summary.check() == null
                ? idx.corpusIndex().referencesOf(rule)
                : CorpusIndex.referencesOf(summary.check());
        List<Dtos.RelatedRule> out = new ArrayList<>();
        for (RuleInsight.RelatedRule r : RuleInsight.related(rule, references, idx.corpusIndex()))
        {
            CorpusAnalysisIndex.RuleSummary other = idx.rules().get(r.coreId());
            String family = RuleFamily.of(r.coreId());
            out.add(new Dtos.RelatedRule(r.coreId(), r.relation(), r.via(),
                    other == null ? family : other.family(),
                    other == null ? CorpusLayout.checkPath(family, r.coreId()) : other.path(),
                    other != null));
        }
        return new Dtos.Related(prepared.branch(), prepared.head(), rule, out);
    }

    /** A view's coordinates and its prepared index request, carried out of the lock. */
    record Prepared(String branch, String head, CorpusAnalysisIndex.Request request)
    {
    }

    // ---- the scenario grid --------------------------------------------------------------

    @PostMapping("/cdt/parse")
    @Operation(summary = "Parse a scenario text into its dataset blocks and prelude directives"
            + " (the engine's own parser; cells in its field encoding)")
    public CdtService.Parsed cdtParse(@AuthenticationPrincipal @Nullable SessionUser aUser,
            @RequestBody Dtos.CdtParse aBody, HttpServletRequest aRequest)
        throws IOException
    {
        requireContent(aBody.content());
        access.requireReader(aUser);
        return CdtService.parse(aBody.content());
    }


    @PostMapping("/cdt/splice")
    @Operation(summary = "Rewrite one dataset block of a scenario text from the grid, addressed"
            + " by its index (else the first block of its name); everything else stays byte for"
            + " byte (400 CDT_UNREPRESENTABLE with the engine's message)")
    public Dtos.CdtText cdtSplice(@AuthenticationPrincipal @Nullable SessionUser aUser,
            @RequestBody Dtos.CdtSplice aBody)
    {
        requireContent(aBody.content());
        if (aBody.dataset() == null)
        {
            throw Errors.badRequest("dataset is required");
        }
        access.requireReader(aUser);
        return new Dtos.CdtText(CdtService.splice(aBody.content(), aBody.dataset(), aBody.index()));
    }


    @PostMapping("/cdt/expectations")
    @Operation(summary = "Replace the #expectViolation* lines of a scenario text with the"
            + " confirmed ones and, with expect, set the #test line's expect= to the run's"
            + " proposal (take actual as expected)")
    public Dtos.CdtText cdtExpectations(@AuthenticationPrincipal @Nullable SessionUser aUser,
            @RequestBody Dtos.CdtExpectations aBody)
    {
        requireContent(aBody.content());
        List<String> lines = aBody.lines();
        if (lines == null)
        {
            // REST review #9: a missing list once wiped the expectations; [] does that on purpose
            throw Errors.badRequest("lines is required ([] clears the expectations)");
        }
        access.requireReader(aUser);
        return new Dtos.CdtText(
                CdtService.withExpectations(aBody.content(), lines, aBody.expect()));
    }

    // ---- the run --------------------------------------------------------------------------


    @PostMapping("/run")
    @Operation(summary = "Run one rule buffer against one scenario buffer in a child JVM worker:"
            + " one run at a time per user or anonymous client (409 RUN_IN_PROGRESS), 503 RUN_BUSY"
            + " beyond the queue, 503 WORKSPACE_BUSY when the workspace stays locked, 429"
            + " RATE_LIMITED for anonymous clients")
    public Dtos.RunResult run(@AuthenticationPrincipal @Nullable SessionUser aUser,
            @RequestBody Dtos.Run aBody, HttpServletRequest aRequest)
        throws IOException
    {
        if (aBody.path() == null || aBody.content() == null || aBody.scenarioPath() == null
                || aBody.scenarioContent() == null)
        {
            throw Errors.badRequest("path, content, scenarioPath and scenarioContent are required");
        }
        String path = PathPolicy.check(aBody.path());
        String scenarioPath = PathPolicy.check(aBody.scenarioPath());
        RunService.requireScenarioPath(scenarioPath);
        requireSize(aBody.content(), "content");
        requireSize(aBody.scenarioContent(), "scenarioContent");
        if (aBody.docs() != null)
        {
            requireSize(aBody.docs(), "docs");
        }
        access.requireReader(aUser);
        boolean anonymous = aUser == null;
        String runner = aUser != null ? RunService.userRunner(aUser.id())
                : RunService.clientRunner(clients.key(aRequest));
        // security review M1 / REST review #1: one run per runner; the export under the
        // workspace lock, the worker outside it — a run never blocks the user's other requests
        try (RunService.Ticket ticket = runs.admit(runner, anonymous))
        {
            ExportedRun exported = access.read(aUser, aRequest,
                    v -> new ExportedRun(v.branch(), v.head(), runs.prepare(v.source(), path,
                            aBody.content(), aBody.docs(), scenarioPath, aBody.scenarioContent())));
            RunReport report = runs.execute(exported.run(), ticket);
            return new Dtos.RunResult(exported.branch(), exported.head(), path, scenarioPath,
                    report);
        }
    }

    /** A run exported under the lock, with the view's coordinates. */
    record ExportedRun(String branch, String head, RunService.PreparedRun run)
    {
    }

    // ---- helpers --------------------------------------------------------------------------

    private void requireContent(@Nullable String aContent)
    {
        if (aContent == null)
        {
            throw Errors.badRequest("content is required");
        }
        requireSize(aContent, "content");
    }


    void requireSize(String aText, String aWhat)
    {
        int bytes = aText.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBufferBytes)
        {
            throw new EditorException(HttpStatus.CONTENT_TOO_LARGE, TOO_LARGE,
                    aWhat + " is " + bytes + " bytes; the limit is " + maxBufferBytes);
        }
    }


    static String requireRuleId(String aRule)
    {
        String rule = aRule.strip();
        if (rule.isEmpty() || rule.indexOf('/') >= 0 || rule.indexOf('\\') >= 0
                || rule.startsWith(".") || rule.length() > 100)
        {
            throw Errors.badRequest("not a rule id: " + aRule);
        }
        return rule;
    }
}
