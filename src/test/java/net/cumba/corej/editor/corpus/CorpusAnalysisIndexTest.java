package net.cumba.corej.editor.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.engine.EngineStore;
import net.cumba.corej.editor.engine.PackageStandards;
import net.cumba.corej.editor.engine.StoreSeedSource;
import net.cumba.corej.editor.engine.StoreSeeding;
import net.cumba.corej.editor.git.ChangedFile;
import net.cumba.corej.editor.git.CorpusSource;
import net.cumba.corej.editor.testing.MapSource;
import net.cumba.corej.editor.testing.RemoteRepo;
import net.cumba.corej.editor.testing.TestStore;
import net.cumba.corej.editor.web.Access;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        return new Access.View(aSource, aSource, "main", aHead, false, () -> aChanges);
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
    void anIndexBuiltWithoutTheStoreDoesNotStickOnceTheStoreIsSeeded(@TempDir Path aDir)
        throws Exception
    {
        // the store appears mid-run (StoreSeeding): the class map of an index built without it
        // must not stick, or the --TERM rule never reaches AETERM until a restart
        try (EngineStore late = new EngineStore(aDir.resolve("metadata-store.zip")))
        {
            CorpusAnalysisIndex lateIndex = new CorpusAnalysisIndex(late, standards);
            Access.View v = view(corpus(), "h1", Map.of());
            var filter = new CorpusAnalysisIndex.Filter("AETERM", null, null, null, null, null,
                    false);
            assertEquals(List.of(RemoteRepo.RULE_B), ids(lateIndex.search(v, "", filter, 100)),
                    "without the store no class reaches AE");
            StoreSeedSource fixture = (aRequest, aStaging) ->
            {
                TestStore.write(aStaging);
                return new StoreSeedSource.Outcome("fixture", 2, 1, List.of(), null);
            };
            new StoreSeeding(late, new EditorProperties(), fixture).start().orElseThrow().get(30,
                    TimeUnit.SECONDS);
            assertTrue(late.available(), late.unavailableReason());
            assertEquals(List.of("CDISC-CG0001", RemoteRepo.RULE_B),
                    ids(lateIndex.search(v, "", filter, 100)),
                    "the same view, re-indexed against the seeded store");
        }
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


    @Test
    void concurrentRequestsForOneViewShareOneBuild() throws Exception
    {
        // REST review #5: no single-flight — every waiting request built the same index again
        CorpusAnalysisIndex fresh = new CorpusAnalysisIndex(store, standards);
        MapSource corpus = corpus();
        CountDownLatch inBuild = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CorpusSource gated = new CorpusSource()
        {

            @Override
            public Optional<byte[]> read(String aPath) throws IOException
            {
                inBuild.countDown();
                try
                {
                    release.await();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                return corpus.read(aPath);
            }


            @Override
            public List<TreeEntry> list(String aDirectory)
            {
                return corpus.list(aDirectory);
            }


            @Override
            public boolean exists(String aPath)
            {
                return corpus.exists(aPath);
            }
        };
        CorpusAnalysisIndex.Request request = new CorpusAnalysisIndex.Request("h-sf", "h-sf",
                Map.of(), gated);
        AtomicReference<CorpusAnalysisIndex.Index> a = new AtomicReference<>();
        AtomicReference<CorpusAnalysisIndex.Index> b = new AtomicReference<>();
        Thread first = new Thread(() -> a.set(build(fresh, request)));
        first.start();
        inBuild.await();
        Thread second = new Thread(() -> b.set(build(fresh, request)));
        second.start();
        Thread.sleep(300); // the second request is waiting for the first build
        release.countDown();
        first.join(30_000);
        second.join(30_000);
        assertSame(a.get(), b.get(), "one build, shared");
        assertEquals(a.get().rules().size(), fresh.summarisedCount(), "every rule summarised once");
    }


    private static CorpusAnalysisIndex.Index build(CorpusAnalysisIndex aIndex,
            CorpusAnalysisIndex.Request aRequest)
    {
        try
        {
            return aIndex.of(aRequest);
        }
        catch (IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    @Test
    void aSaveAtTheSameHeadRebuildsOnlyItsRuleAndEqualsAFullBuild() throws IOException
    {
        // REST review #5: every save rebuilt the whole corpus
        CorpusAnalysisIndex incremental = new CorpusAnalysisIndex(store, standards);
        MapSource source = corpus();
        CorpusAnalysisIndex.Index clean = incremental.of(view(source, "h-inc", Map.of()));
        int afterFull = incremental.summarisedCount();
        assertEquals(clean.rules().size(), afterFull);
        source.put(RemoteRepo.CHECK_B,
                RemoteRepo.CHECK_B_TEXT.replace("AETERM == \"\"", "AETERM == \"X\""));
        Map<String, ChangedFile.Kind> modified = Map.of(RemoteRepo.CHECK_B,
                ChangedFile.Kind.MODIFIED);
        CorpusAnalysisIndex.Index edited = incremental.of(view(source, "h-inc", modified));
        assertEquals(afterFull + 1, incremental.summarisedCount(), "only CG0270 again");
        CorpusAnalysisIndex.Index full = new CorpusAnalysisIndex(store, standards)
                .of(view(source, "h-inc", modified));
        assertEquals(full.rules(), edited.rules(), "the incremental index IS the full one");
        assertTrue(edited.rules().get(RemoteRepo.RULE_B).checkText().contains("\"X\""));
        // a new rule's check and scenario, then their discard (back to the clean state)
        String newCheck = CorpusLayout.checkPath("CDISC", "CDISC-CG0272");
        String newScenario = CorpusLayout.scenarioDir("CDISC", "CDISC-CG0272") + "/s.cdt";
        source.put(newCheck, RemoteRepo.CHECK_B_TEXT.replace("CDISC-CG0270", "CDISC-CG0272"))
                .put(newScenario, RemoteRepo.SCENARIO_B_INVALID_TEXT);
        Map<String, ChangedFile.Kind> added = Map.of(RemoteRepo.CHECK_B, ChangedFile.Kind.MODIFIED,
                newCheck, ChangedFile.Kind.UNTRACKED, newScenario, ChangedFile.Kind.UNTRACKED);
        CorpusAnalysisIndex.Index withNew = incremental.of(view(source, "h-inc", added));
        assertTrue(withNew.rules().get("CDISC-CG0272").hasScenarios());
        assertEquals(
                new CorpusAnalysisIndex(store, standards).of(view(source, "h-inc", added)).rules(),
                withNew.rules());
        // a rule MOVED between families, copy then delete: the family is the one holding the
        // check now (review: the incremental build took the last changed path's family, and the
        // deleted CDISC path sorts after the new ADAM one)
        CorpusAnalysisIndex moving = new CorpusAnalysisIndex(store, standards);
        MapSource moved = corpus();
        moving.of(view(moved, "h-move", Map.of()));
        String adam = CorpusLayout.checkPath("ADAM", RemoteRepo.RULE_B);
        moved.put(adam, RemoteRepo.CHECK_B_TEXT);
        Map<String, ChangedFile.Kind> copied = Map.of(adam, ChangedFile.Kind.UNTRACKED);
        CorpusAnalysisIndex.Index both = moving.of(view(moved, "h-move", copied));
        assertEquals(
                new CorpusAnalysisIndex(store, standards).of(view(moved, "h-move", copied)).rules(),
                both.rules(), "in two families at once: the full build's answer");
        moved.remove(RemoteRepo.CHECK_B);
        Map<String, ChangedFile.Kind> deleted = Map.of(adam, ChangedFile.Kind.UNTRACKED,
                RemoteRepo.CHECK_B, ChangedFile.Kind.DELETED);
        CorpusAnalysisIndex.Index after = moving.of(view(moved, "h-move", deleted));
        assertEquals("ADAM", after.rules().get(RemoteRepo.RULE_B).family());
        assertEquals(new CorpusAnalysisIndex(store, standards).of(view(moved, "h-move", deleted))
                .rules(), after.rules(), "moved: the incremental index IS the full one");
    }


    @Test
    void theIndexIsBuiltFromWhatTheLockedPartRead() throws IOException
    {
        // REST review #5: built outside the lock, so it must not read the live tree
        MapSource source = corpus();
        source.put(RemoteRepo.CHECK_B,
                RemoteRepo.CHECK_B_TEXT.replace("AETERM == \"\"", "AETERM == \"SEEN\""));
        CorpusAnalysisIndex.Request request = CorpusAnalysisIndex.prepare(
                view(source, "h-snap", Map.of(RemoteRepo.CHECK_B, ChangedFile.Kind.MODIFIED)));
        source.put(RemoteRepo.CHECK_B,
                RemoteRepo.CHECK_B_TEXT.replace("AETERM == \"\"", "AETERM == \"LATER\""));
        CorpusAnalysisIndex.Index idx = new CorpusAnalysisIndex(store, standards).of(request);
        String text = idx.rules().get(RemoteRepo.RULE_B).checkText();
        assertTrue(text.contains("SEEN") && !text.contains("LATER"), text);
    }
}
