package net.cumba.corej.editor.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import net.cumba.corej.editor.corpus.Explorer;
import net.cumba.corej.editor.corpus.RuleSearch;
import net.cumba.corej.editor.engine.Analysis;
import net.cumba.corej.editor.engine.CdtService;
import net.cumba.corej.editor.engine.FunctionCatalogue;
import net.cumba.corej.editor.engine.ScenarioCatalogue;
import net.cumba.corej.editor.git.BranchInfo;
import net.cumba.corej.editor.git.ChangedFile;
import net.cumba.corej.editor.git.CommitInfo;
import net.cumba.corej.editor.git.CorpusSource;
import net.cumba.corej.editor.git.MergeOutcome;
import net.cumba.corej.editor.git.PushOutcome;
import net.cumba.corej.editor.git.UnpushedInfo;
import net.cumba.corej.editor.run.RunReport;
import net.cumba.corej.editor.session.AccessMode;
import org.jspecify.annotations.Nullable;

/**
 * The request and response shapes of the API, one record each. Every mutating request carries the
 * client's {@code branch} and {@code head} (and {@code blob} for a file write), the §5.8.4
 * optimistic-concurrency triple; a mismatch is 409 {@code STALE}.
 */
public final class Dtos
{

    private Dtos()
    {
    }

    // ---- session -----------------------------------------------------------------------

    /** {@code POST /api/session/login}. */
    public record LoginRequest(
            @Schema(description = "the forge login name", example = "mw") String login,
            @Schema(description = "a personal access token of that login") String token)
    {
    }


    /** {@code GET /api/session/csrf}. */
    public record CsrfInfo(@Schema(
            description = "the header a mutating request sends the token in") String headerName,
            String parameterName, String token)
    {
    }


    /** The authenticated session. */
    public record SessionInfo(long id, String login, String name, String email, AccessMode mode,
            @Schema(description = "the repository's default branch; never writable") String defaultBranch)
    {
    }

    // ---- info --------------------------------------------------------------------------


    /** {@code GET /api/info}: the instance and the repository it is bound to. */
    public record ServiceInfo(String service, String version, String commit,
            RepositoryInfo repository,
            @Schema(description = "anonymous read access is served right now") boolean anonymousRead,
            EngineInfo engine)
    {
    }


    /**
     * The engine this instance runs (phase 3): its version, its rule-language level (the banner
     * compares it with the branch's {@code rules-src/LANGUAGE-LEVEL}, O13), the metadata store and
     * the run limits.
     */
    public record EngineInfo(@Nullable String version, int languageLevel, boolean storeAvailable,
            @Schema(description = "the open store's format version, -1 without a store") int storeFormat,
            @Schema(description = "why there is no store; empty when there is one") String storeReason,
            long runTimeoutSeconds, int maxBufferBytes)
    {
    }


    /** The bound repository. */
    public record RepositoryInfo(String url, String owner, String name, String forge)
    {
    }

    // ---- workspace state ----------------------------------------------------------------


    /** {@code GET /api/status}: the dirty states the server knows (unsaved is the browser's). */
    public record Status(String branch, String head, String defaultBranch, boolean readOnly,
            @Schema(description = "saved, uncommitted changes under the editable trees") List<ChangedFile> uncommitted,
            @Schema(description = "changes outside the editable trees (never staged)") List<ChangedFile> ignored,
            UnpushedInfo unpushed)
    {

        public Status
        {
            uncommitted = List.copyOf(uncommitted);
            ignored = List.copyOf(ignored);
        }
    }


    /** {@code GET /api/branches}. */
    public record Branches(String current, String defaultBranch, String head,
            List<BranchInfo> branches)
    {

        public Branches
        {
            branches = List.copyOf(branches);
        }
    }


    /** The §5.8.4 expectation alone (push, update-from-*). */
    public record Expect(String branch, String head)
    {
    }


    /** {@code POST /api/branches}: a new branch from the remote default branch. */
    public record CreateBranch(String name, String branch, String head)
    {
    }


    /** {@code POST /api/branches/switch}. */
    public record SwitchBranch(String name, String branch, String head)
    {
    }


    /** {@code POST /api/branches/delete}: a local branch holding nothing unpushed. */
    public record DeleteBranch(String name)
    {
    }


    /** {@code POST /api/commit}. */
    public record Commit(String message, String branch, String head)
    {
    }


    /** A commit's result. */
    public record Committed(CommitInfo commit, String branch, String head)
    {
    }


    /** A push's result. */
    public record Pushed(PushOutcome push, @Schema(
            description = "the forge's create-pull-request page for the branch") String pullRequestUrl)
    {
    }


    /** An update-from-* result. */
    public record Updated(MergeOutcome merge, String branch, String head)
    {
    }


    /**
     * {@code POST /api/discard}: the files the user confirmed losing — they must be exactly the
     * current uncommitted files under the editable trees, else 409 {@code STALE}.
     */
    public record Discard(@Schema(
            description = "the uncommitted paths the user confirmed losing (status.uncommitted)") @Nullable List<String> paths,
            String branch, String head)
    {

        public Discard
        {
            paths = paths == null ? null : List.copyOf(paths);
        }
    }


    /** A discard's result. */
    public record Discarded(
            @Schema(description = "the changes discarded") List<ChangedFile> discarded,
            String branch, String head)
    {

        public Discarded
        {
            discarded = List.copyOf(discarded);
        }
    }

    // ---- files -------------------------------------------------------------------------


    /** {@code GET /api/files?path=}: one directory level. */
    public record Directory(String path, List<CorpusSource.TreeEntry> entries, String branch,
            String head)
    {

        public Directory
        {
            entries = List.copyOf(entries);
        }
    }


    /** {@code GET /api/files/content?path=}. */
    public record FileContent(String path, String content, @Schema(
            description = "the git blob id of the content; send it back on write") String blob,
            String branch, String head)
    {
    }


    /** {@code PUT /api/files/content}. */
    public record WriteFile(String path, String content, String branch, String head,
            @Schema(description = "the blob read; null when creating a file") @Nullable String blob)
    {
    }


    /** A write's result. */
    public record Written(String path, String blob, String branch, String head)
    {
    }

    // ---- explorer and search ------------------------------------------------------------


    /** {@code GET /api/explorer}. */
    public record ExplorerTree(String branch, String head, boolean anonymous,
            List<Explorer.Family> families,
            @Schema(description = "the branch's rules-src/LANGUAGE-LEVEL, null when it declares none") @Nullable Integer languageLevel)
    {

        public ExplorerTree
        {
            families = List.copyOf(families);
        }
    }


    /** {@code GET /api/search?q=}. */
    public record Search(String branch, String head, String query, List<RuleSearch.Hit> hits,
            int total, boolean truncated)
    {

        public Search
        {
            hits = List.copyOf(hits);
        }
    }

    // ---- the engine (phase 3) -----------------------------------------------------------


    /** {@code POST /api/analyse}: a rule buffer (and the docs buffer when the client holds one). */
    public record Analyse(String path, String content, @Nullable String docs)
    {
    }


    /** The analysis of a buffer on the view's branch. */
    public record Analysed(String branch, String head, Analysis analysis)
    {
    }


    /** {@code GET /api/engine/functions}. */
    public record Functions(List<FunctionCatalogue.FunctionInfo> functions)
    {

        public Functions
        {
            functions = List.copyOf(functions);
        }
    }


    /** {@code GET /api/scenarios?rule=}. */
    public record Scenarios(String branch, String head, String rule, String family,
            ScenarioCatalogue.Suite suite)
    {
    }


    /** One related rule with where it lives (§5.5). */
    public record RelatedRule(String coreId, String relation, String via, String family,
            String path, boolean exists)
    {
    }


    /** {@code GET /api/related?rule=}. */
    public record Related(String branch, String head, String rule, List<RelatedRule> related)
    {

        public Related
        {
            related = List.copyOf(related);
        }
    }


    /** {@code POST /api/cdt/parse}. */
    public record CdtParse(String content)
    {
    }


    /** {@code POST /api/cdt/splice}: the text and the one dataset block the grid rewrote. */
    public record CdtSplice(String content, CdtService.Dataset dataset)
    {
    }


    /** {@code POST /api/cdt/expectations}: the text and the confirmed expectation lines. */
    public record CdtExpectations(String content, List<String> lines)
    {

        public CdtExpectations
        {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }


    /** A rewritten scenario text. */
    public record CdtText(String content)
    {
    }


    /** {@code POST /api/run}: the rule buffer and the scenario buffer (O8). */
    public record Run(String path, String content, @Nullable String docs, String scenarioPath,
            String scenarioContent)
    {
    }


    /** The judged run. */
    public record RunResult(String branch, String head, RunReport report)
    {
    }


    /** {@code GET /api/engine/ct?codelist=}: a codelist of the store's CT, newest package first. */
    public record CodelistLookup(String query, List<CodelistHit> hits)
    {

        public CodelistLookup
        {
            hits = List.copyOf(hits);
        }
    }


    /** One codelist as one CT package publishes it. */
    public record CodelistHit(String ctPackage, @Nullable String conceptId,
            @Nullable String submissionValue, @Nullable String name, @Nullable String preferredTerm,
            @Nullable String definition, @Nullable Boolean extensible, int termCount,
            List<CodelistTerm> terms)
    {

        public CodelistHit
        {
            terms = List.copyOf(terms);
        }
    }


    public record CodelistTerm(@Nullable String conceptId, @Nullable String submissionValue,
            @Nullable String preferredTerm, @Nullable String definition)
    {
    }
}
