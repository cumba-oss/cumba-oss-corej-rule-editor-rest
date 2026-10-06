package net.cumba.corej.editor.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import net.cumba.corej.editor.engine.EngineStore;
import net.cumba.corej.editor.engine.PackageStandards;
import net.cumba.corej.editor.git.ChangedFile;
import net.cumba.corej.editor.testing.MapSource;
import net.cumba.corej.editor.testing.RemoteRepo;
import net.cumba.corej.editor.testing.TestStore;
import net.cumba.corej.editor.web.Access;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The semantic search over the assembled corpus and its per-view cache (§5.3). */
class CorpusAnalysisIndexTest
{

    static final String CHECK_C = CorpusLayout.checkPath("CDISC", "CDISC-CG0001");

    /** An Events-class rule naming --TERM: AETERM must find it through the store's classes. */
    static final String CHECK_C_TEXT = """
            Core:
              Id: "CDISC-CG0001"
              Status: "Draft"
            Description: "The term is present."
            Scope:
              Classes:
                Include:
                - "EVENTS"
            Check:
              expression: >-
                empty(--TERM) and var_exists("--DECOD")
            Standards:
            - Organization: "CDISC"
              Standards:
              - Name: "SDTMIG"
                Version: "3.4"
                References:
                - Origin: "SDTM and SDTMIG Conformance Rules"
                  Rule_Identifier:
                    Id: "CG0001"
                    Version: "1"
                  Version: "2.0"
            """;

    private static EngineStore store;

    private static PackageStandards standards;

    private static CorpusAnalysisIndex index;

    @BeforeAll
    static void open() throws IOException
    {
        store = new EngineStore(TestStore.file());
        standards = new PackageStandards();
        index = new CorpusAnalysisIndex(store, standards);
    }


    @AfterAll
    static void close() throws IOException
    {
        store.close();
        standards.close();
    }


    static MapSource corpus()
    {
        return new MapSource(RemoteRepo.INITIAL_FILES).put(CHECK_C, CHECK_C_TEXT);
    }


    static Access.View view(MapSource aSource, String aHead, Map<String, ChangedFile.Kind> aChanges)
    {
        return new Access.View(aSource, "main", aHead, aChanges, false);
    }


    static List<String> ids(RuleSearch.Result aResult)
    {
        return aResult.hits().stream().map(RuleSearch.Hit::id).toList();
    }


    @Test
    void theIndexSummarisesEveryRule() throws IOException
    {
        CorpusAnalysisIndex.Index idx = index.of(view(corpus(), "h1", Map.of()));
        assertEquals(4, idx.rules().size());
        CorpusAnalysisIndex.RuleSummary b = idx.rules().get(RemoteRepo.RULE_B);
        assertEquals("CDISC", b.family());
        assertEquals("Published", b.status());
        assertTrue(b.loaded());
        assertTrue(b.referenced().containsAll(List.of("AETERM", "AEDECOD")));
        assertEquals(List.of("AE"), List.copyOf(b.domains()));
        assertFalse(b.allDomains());
        assertTrue(b.hasScenarios());
        assertEquals("SDTMIG", b.standards().get(0).name());
        CorpusAnalysisIndex.RuleSummary a = idx.rules().get(RemoteRepo.RULE_A);
        assertTrue(a.allDomains());
        assertEquals(List.of("ds_exists"), List.copyOf(a.functions()));
        assertTrue(a.hasScenarios());
        assertEquals(RemoteRepo.DOCS_A, a.docsPath());
        CorpusAnalysisIndex.RuleSummary c = idx.rules().get("CDISC-CG0001");
        assertTrue(c.referenced().contains("--TERM"), c.referenced().toString());
        assertTrue(c.referenced().contains("--DECOD"), "a quoted var_exists name is a reference");
        assertEquals(List.of("EVENTS"), List.copyOf(c.classes()));
        assertFalse(c.hasScenarios());
        assertTrue(idx.corpusIndex().size() >= 4);
    }


    @Test
    void mentionsVariableResolvesWildcardsThroughTheScope() throws IOException
    {
        Access.View v = view(corpus(), "h1", Map.of());
        var filter = new CorpusAnalysisIndex.Filter("AETERM", null, null, null, null, null, false);
        assertEquals(List.of("CDISC-CG0001", RemoteRepo.RULE_B),
                ids(index.search(v, "", filter, 100)),
                "the direct AETERM rule and the --TERM rule scoped to Events through AE");
        var tsterm = new CorpusAnalysisIndex.Filter("TSTERM", null, null, null, null, null, false);
        assertEquals(List.of(), ids(index.search(v, "", tsterm, 100)),
                "TS is Trial Design, not Events: the --TERM rule does not reach TSTERM");
        var wildcard = new CorpusAnalysisIndex.Filter("--DECOD", null, null, null, null, null,
                false);
        assertEquals(List.of("CDISC-CG0001", RemoteRepo.RULE_B),
                ids(index.search(v, "", wildcard, 100)),
                "a -- query finds the -- form and its concrete spellings");
        var lower = new CorpusAnalysisIndex.Filter("aeterm", null, null, null, null, null, false);
        assertEquals(2, index.search(v, "", lower, 100).total(), "case-insensitive");
    }


    @Test
    void theOtherFiltersAndTheFreeTextAreAnded() throws IOException
    {
        Access.View v = view(corpus(), "h1", Map.of());
        assertEquals(List.of("CDISC-CG0001", RemoteRepo.RULE_B), ids(index.search(v, "",
                new CorpusAnalysisIndex.Filter(null, "AE", null, null, null, null, false), 100)),
                "scoped to AE: the explicit domain and the Events class AE belongs to; never ALL");
        assertEquals(List.of(), ids(index.search(v, "",
                new CorpusAnalysisIndex.Filter(null, "DM", null, null, null, null, false), 100)),
                "AD0001's ALL is not 'scoped to DM'");
        assertEquals(List.of("CDISC-CG0001"), ids(index.search(v, "",
                new CorpusAnalysisIndex.Filter(null, "events", null, null, null, null, false),
                100)), "a class, case-insensitive");
        assertEquals(List.of(RemoteRepo.RULE_A), ids(index.search(v, "",
                new CorpusAnalysisIndex.Filter(null, null, "ds_exists", null, null, null, false),
                100)));
        assertEquals(List.of("CDISC-CG0001"), ids(index.search(v, "",
                new CorpusAnalysisIndex.Filter(null, null, null, "draft", null, null, false),
                100)));
        assertEquals(List.of("CDISC-CG0001", RemoteRepo.RULE_B), ids(index.search(v, "",
                new CorpusAnalysisIndex.Filter(null, null, null, null, "sdtmig", "3.4", false),
                100)));
        assertEquals(List.of(), ids(index.search(v, "",
                new CorpusAnalysisIndex.Filter(null, null, null, null, "SDTMIG", "3.3", false),
                100)));
        assertEquals(List.of("CDISC-CG0001", RemoteRepo.RULE_F), ids(index.search(v, "",
                new CorpusAnalysisIndex.Filter(null, null, null, null, null, null, true), 100)),
                "no scenarios");
        RuleSearch.Result both = index.search(v, "present",
                new CorpusAnalysisIndex.Filter(null, null, null, null, null, null, true), 100);
        assertEquals(List.of("CDISC-CG0001"), ids(both));
        assertEquals(1, both.total());
        assertFalse(both.hits().get(0).snippets().isEmpty(),
                "free-text snippets still come with a filtered hit");
        assertEquals(0,
                index.search(v, "",
                        new CorpusAnalysisIndex.Filter(null, null, null, null, null, null, false),
                        100).total(),
                "no text and no filter: nothing, as the free-text search answers");
        RuleSearch.Result limited = index.search(v, "",
                new CorpusAnalysisIndex.Filter(null, null, null, null, "SDTMIG", null, false), 1);
        assertEquals(1, limited.hits().size());
        assertEquals(2, limited.total());
        assertTrue(limited.truncated());
    }


    @Test
    void theCacheKeyIsTheHeadAndTheChangedContent() throws IOException
    {
        MapSource source = corpus();
        CorpusAnalysisIndex.Index first = index.of(view(source, "h2", Map.of()));
        assertSame(first, index.of(view(source, "h2", Map.of())), "same view: a hit");
        assertNotEquals(first, index.of(view(source, "h3", Map.of())), "a new HEAD rebuilds");
        Map<String, ChangedFile.Kind> modified = Map.of(RemoteRepo.CHECK_B,
                ChangedFile.Kind.MODIFIED);
        CorpusAnalysisIndex.Index dirty = index.of(view(source, "h2", modified));
        assertNotEquals(first, dirty, "an uncommitted file is part of the key");
        source.put(RemoteRepo.CHECK_B,
                RemoteRepo.CHECK_B_TEXT.replace("AETERM == \"\"", "AETERM == \"X\""));
        CorpusAnalysisIndex.Index edited = index.of(view(source, "h2", modified));
        assertNotEquals(dirty, edited, "the same path with other content is another key");
        assertEquals(CorpusAnalysisIndex.key(view(source, "h2", Map.of())),
                CorpusAnalysisIndex
                        .key(view(source, "h2", Map.of("pom.xml", ChangedFile.Kind.MODIFIED))),
                "a change outside the corpus trees does not enter the key");
    }
}
