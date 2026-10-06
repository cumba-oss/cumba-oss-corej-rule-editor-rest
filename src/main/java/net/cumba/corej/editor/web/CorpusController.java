package net.cumba.corej.editor.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.corpus.CorpusAnalysisIndex;
import net.cumba.corej.editor.corpus.Explorer;
import net.cumba.corej.editor.corpus.RuleSearch;
import net.cumba.corej.editor.engine.CorpusText;
import net.cumba.corej.editor.error.EditorException;
import net.cumba.corej.editor.run.RateLimiter;
import net.cumba.corej.editor.session.SessionUser;
import net.cumba.corej.editor.web.dto.Dtos;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Explorer tree and the free-text search (§5.3), over the session's tree or the snapshot. An
 * anonymous search is rate-limited per client and its {@code limit} capped (security review L12); a
 * filtered search prepares the corpus index under the workspace lock and searches outside it (REST
 * review #5).
 */
@RestController
@RequestMapping("/api")
@Tag(name = "corpus", description = "Explorer tree and free-text search")
public class CorpusController
{

    private final Access access;

    private final CorpusAnalysisIndex index;

    private final ClientAddress clients;

    private final RateLimiter anonymousSearches;

    private final int anonymousSearchesPerMinute;

    private final int anonymousMaxResults;

    public CorpusController(Access aAccess, CorpusAnalysisIndex aIndex,
            EditorProperties aProperties, ClientAddress aClients)
    {
        access = aAccess;
        index = aIndex;
        clients = aClients;
        anonymousSearchesPerMinute = aProperties.getAnonymous().getSearchesPerMinute();
        anonymousSearches = new RateLimiter(anonymousSearchesPerMinute, Duration.ofMinutes(1));
        anonymousMaxResults = Math.max(1, aProperties.getAnonymous().getMaxSearchResults());
    }


    @GetMapping("/explorer")
    @Operation(summary = "Families and their rules, with NEW / MODIFIED markers from git status")
    public Dtos.ExplorerTree explorer(@AuthenticationPrincipal @Nullable SessionUser aUser,
            HttpServletRequest aRequest)
        throws IOException
    {
        return access.read(aUser, aRequest,
                v -> new Dtos.ExplorerTree(v.branch(), v.head(), v.anonymous(),
                        Explorer.families(v.source(), v.changes()),
                        CorpusText.languageLevel(v.source())));
    }


    @GetMapping("/search")
    @Operation(summary = "Free text over check and docs YAML (words ANDed, quotes make a phrase),"
            + " plus the semantic filters: mentions a variable (--X resolved through the scope),"
            + " scoped to a domain / class, uses a function, status, standard + version,"
            + " has no scenarios — all ANDed")
    public Dtos.Search search(@AuthenticationPrincipal @Nullable SessionUser aUser,
            @RequestParam(name = "q", defaultValue = "") String aQuery,
            @RequestParam(name = "variable", required = false) @Nullable String aVariable,
            @RequestParam(name = "scope", required = false) @Nullable String aScope,
            @RequestParam(name = "function", required = false) @Nullable String aFunction,
            @RequestParam(name = "status", required = false) @Nullable String aStatus,
            @RequestParam(name = "standard", required = false) @Nullable String aStandard,
            @RequestParam(name = "version", required = false) @Nullable String aVersion,
            @RequestParam(name = "noScenarios", defaultValue = "false") boolean aNoScenarios,
            @RequestParam(name = "limit", defaultValue = "200") int aLimit,
            HttpServletRequest aRequest)
        throws IOException
    {
        CorpusAnalysisIndex.Filter filter = new CorpusAnalysisIndex.Filter(aVariable, aScope,
                aFunction, aStatus, aStandard, aVersion, aNoScenarios);
        int limit = aLimit;
        if (aUser == null)
        {
            if (!anonymousSearches.tryAcquire(clients.key(aRequest)))
            {
                throw new EditorException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "anonymous searches are limited to " + anonymousSearchesPerMinute
                                + " per minute per client");
            }
            limit = Math.min(limit, anonymousMaxResults);
        }
        int max = limit;
        if (filter.isEmpty())
        {
            return access.read(aUser, aRequest, v ->
            {
                RuleSearch.Result r = RuleSearch.search(v.source(), aQuery, max);
                return new Dtos.Search(v.branch(), v.head(), r.query(), r.hits(), r.total(),
                        r.truncated());
            });
        }
        EngineController.Prepared prepared = access.read(aUser, aRequest,
                v -> new EngineController.Prepared(v.branch(), v.head(),
                        CorpusAnalysisIndex.prepare(v)));
        RuleSearch.Result r = index.search(prepared.request(), aQuery, filter, max);
        return new Dtos.Search(prepared.branch(), prepared.head(), r.query(), r.hits(), r.total(),
                r.truncated());
    }
}
