package net.cumba.corej.editor.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.cumba.corej.editor.git.ChangedFile;
import net.cumba.corej.editor.git.CorpusSource;
import net.cumba.corej.editor.git.WorkingTreeSource;
import net.cumba.corej.editor.testing.RemoteRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The free-text search and the explorer over a working tree holding the miniature corpus. */
class RuleSearchTest
{

    @TempDir
    Path tmp;

    private CorpusSource source;

    @BeforeEach
    void write() throws IOException
    {
        for (Map.Entry<String, String> f : RemoteRepo.INITIAL_FILES.entrySet())
        {
            Path p = tmp.resolve(f.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getValue(), StandardCharsets.UTF_8);
        }
        source = new WorkingTreeSource(tmp);
    }


    @Test
    void parseSplitsWordsAndQuotedPhrases()
    {
        assertEquals(
                List.of(new RuleSearch.Term("adsl", false),
                        new RuleSearch.Term("not present", true), new RuleSearch.Term("x", false),
                        new RuleSearch.Term("to the end", true)),
                RuleSearch.parse("  ADSL \"Not Present\" x 'to the end"));
        assertTrue(RuleSearch.parse("   ").isEmpty());
        assertTrue(RuleSearch.parse("\"\"").isEmpty());
    }


    @Test
    void wordsAreAndedAcrossCheckAndDocsCaseInsensitively() throws IOException
    {
        RuleSearch.Result r = RuleSearch.search(source, "ADSL", 10);
        assertEquals(1, r.total());
        RuleSearch.Hit hit = r.hits().get(0);
        assertEquals(RemoteRepo.RULE_A, hit.id());
        assertEquals("CDISC", hit.family());
        assertEquals(RemoteRepo.CHECK_A, hit.path());
        assertEquals(RemoteRepo.DOCS_A, hit.docsPath());
        assertEquals("Raise an error when the ADSL dataset is not present.", hit.description());
        assertTrue(hit.snippets().stream().anyMatch(s -> s.file().equals("check")));
        assertTrue(hit.snippets().stream().anyMatch(s -> s.file().equals("docs")
                && s.text().contains("ADSL and its related metadata")));

        assertEquals(1, RuleSearch.search(source, "related metadata", 10).total(),
                "a docs-only term finds the rule");
        assertEquals(1, RuleSearch.search(source, "\"not present\"", 10).total());
        assertEquals(0, RuleSearch.search(source, "\"present not\"", 10).total());
        assertEquals(0, RuleSearch.search(source, "adsl aeterm", 10).total(), "AND");
        assertEquals(2, RuleSearch.search(source, "cdisc", 10).total(), "the id matches too");
        assertEquals(1, RuleSearch.search(source, "fda-sd1142", 10).total());
        assertEquals(0, RuleSearch.search(source, "", 10).total());
        RuleSearch.Hit f = RuleSearch.search(source, "dataset_size", 10).hits().get(0);
        assertNull(f.docsPath(), "no docs sidecar for FDA-SD1142");
    }


    @Test
    void limitTruncatesButTotalCounts() throws IOException
    {
        RuleSearch.Result r = RuleSearch.search(source, "core", 1);
        assertEquals(1, r.hits().size());
        assertEquals(3, r.total());
        assertTrue(r.truncated());
        assertFalse(RuleSearch.search(source, "core", 100).truncated());
        assertEquals(1, RuleSearch.search(source, "core", -5).hits().size(), "floor 1");
    }


    @Test
    void anUnparsableBufferIsStillSearchableWithoutADescription() throws IOException
    {
        Files.writeString(tmp.resolve(RemoteRepo.CHECK_B), "Description: [broken\nCheck: x\n");
        RuleSearch.Hit b = RuleSearch.search(source, "broken", 10).hits().get(0);
        assertEquals(RemoteRepo.RULE_B, b.id());
        assertEquals("", b.description());
        assertEquals("", RuleSearch.description("- a list, not a map\n"));
        assertEquals("", RuleSearch.description(""));
    }


    @Test
    void explorerListsFamiliesRulesDocsAndMarkers() throws IOException
    {
        List<Explorer.Family> families = Explorer.families(source,
                Map.of(RemoteRepo.CHECK_B, ChangedFile.Kind.MODIFIED, RemoteRepo.DOCS_A,
                        ChangedFile.Kind.MODIFIED, RemoteRepo.CHECK_F, ChangedFile.Kind.UNTRACKED));
        assertEquals(List.of("CDISC", "FDA"),
                families.stream().map(Explorer.Family::name).toList());
        Explorer.Family cdisc = families.get(0);
        assertEquals(List.of(RemoteRepo.RULE_A, RemoteRepo.RULE_B),
                cdisc.rules().stream().map(Explorer.Rule::id).toList());
        Explorer.Rule a = cdisc.rules().get(0);
        assertEquals(RemoteRepo.DOCS_A, a.docsPath());
        assertEquals(Explorer.Marker.MODIFIED, a.marker(), "a docs-only change marks the rule");
        assertEquals(Explorer.Marker.MODIFIED, cdisc.rules().get(1).marker());
        assertNull(cdisc.rules().get(1).docsPath());
        assertEquals(Explorer.Marker.NEW, families.get(1).rules().get(0).marker());
        assertEquals(Explorer.Marker.CLEAN,
                Explorer.families(source, Map.of()).get(0).rules().get(0).marker());
        assertEquals(Explorer.Marker.NEW, Explorer.marker(ChangedFile.Kind.ADDED, null));
        assertEquals(Explorer.Marker.MODIFIED, Explorer.marker(ChangedFile.Kind.DELETED, null));
    }
}
