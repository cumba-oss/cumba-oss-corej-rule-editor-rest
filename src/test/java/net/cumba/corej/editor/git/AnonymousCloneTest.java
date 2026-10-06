package net.cumba.corej.editor.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.corpus.CorpusLayout;
import net.cumba.corej.editor.testing.FakeForge;
import net.cumba.corej.editor.testing.RemoteRepo;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** §5.1.6: a bare clone, snapshot reads, re-probe on every refresh, self-disabling. */
class AnonymousCloneTest
{

    @TempDir
    Path tmp;

    private RemoteRepo remote;

    private FakeForge forge;

    private EditorProperties props;

    private AnonymousClone clone;

    @BeforeEach
    void setUp() throws IOException, GitAPIException
    {
        remote = RemoteRepo.create(tmp.resolve("remote"));
        forge = new FakeForge();
        forge.setAnonymouslyReadable(true);
        props = new EditorProperties();
        props.getRepository().setUrl(remote.url());
        props.getRepository().setAllowFile(true); // the fixture remote is file:// (L3: tests only)
        props.setWorkspaceRoot(tmp.resolve("workspaces").toString());
        props.getAnonymous().setEnabled(true);
    }


    @AfterEach
    void tearDown()
    {
        if (clone != null)
        {
            clone.stop();
        }
        remote.close();
    }


    @Test
    void servesASnapshotOfTheRemoteHeadBranchAndFollowsIt() throws IOException, GitAPIException
    {
        clone = new AnonymousClone(props, forge);
        clone.start();
        assertTrue(clone.available());
        AnonymousClone.Snapshot s = clone.snapshot().orElseThrow();
        assertEquals("main", s.branch(), "the remote's HEAD symref");
        assertEquals(remote.tip("main"), s.commit());
        CorpusSource src = s.source();
        assertTrue(src.exists(RemoteRepo.CHECK_A));
        assertFalse(src.exists("README.md.nope"));
        assertTrue(new String(src.read(RemoteRepo.CHECK_A).orElseThrow(), StandardCharsets.UTF_8)
                .contains("CDISC-AD0001"));
        assertEquals(List.of("CDISC", "FDA"),
                src.list(CorpusLayout.CHECKS).stream().map(CorpusSource.TreeEntry::name).toList());
        assertTrue(src.list("rules-src/nope").isEmpty());
        assertTrue(
                Files.isDirectory(
                        tmp.resolve("workspaces").resolve(AnonymousClone.DIR).resolve("objects")),
                "a bare clone, no working tree");

        remote.commitAndPush("main", Map.of(RemoteRepo.CHECK_F, "Core: newer\n"), "newer");
        assertEquals(s.commit(), clone.snapshot().orElseThrow().commit(), "until a refresh");
        clone.scheduledRefresh();
        AnonymousClone.Snapshot s2 = clone.snapshot().orElseThrow();
        assertEquals(remote.tip("main"), s2.commit());
        assertEquals("Core: newer\n", new String(s2.source().read(RemoteRepo.CHECK_F).orElseThrow(),
                StandardCharsets.UTF_8));
        assertTrue(src.read(RemoteRepo.CHECK_F).orElseThrow().length > 13,
                "the old snapshot still reads the old commit");
    }


    @Test
    void switchesItselfOffWhenTheRepositoryStopsBeingPublic()
    {
        clone = new AnonymousClone(props, forge);
        clone.start();
        assertTrue(clone.available());
        forge.setAnonymouslyReadable(false);
        clone.scheduledRefresh();
        assertFalse(clone.available());
        assertTrue(clone.snapshot().isEmpty());
        forge.setAnonymouslyReadable(true);
        clone.scheduledRefresh();
        assertFalse(clone.available(), "once off, the scheduled refresh does not re-enable");
        clone.refresh();
        assertTrue(clone.available(), "an explicit refresh does");
    }


    @Test
    void disabledByConfigurationNeverProbesOrClones()
    {
        props.getAnonymous().setEnabled(false);
        clone = new AnonymousClone(props, forge);
        clone.start();
        clone.scheduledRefresh();
        assertFalse(clone.available());
        assertFalse(Files.exists(tmp.resolve("workspaces").resolve(AnonymousClone.DIR)));
    }


    @Test
    void aConfiguredDefaultBranchOverridesTheRemoteHead() throws IOException, GitAPIException
    {
        remote.commitAndPush("release", Map.of("README.md", "# release\n"), "release branch");
        props.getRepository().setDefaultBranch("release");
        clone = new AnonymousClone(props, forge);
        clone.start();
        assertEquals("release", clone.snapshot().orElseThrow().branch());
        assertEquals(remote.tip("release"), clone.snapshot().orElseThrow().commit());

        props.getRepository().setDefaultBranch("nope");
        AnonymousClone broken = new AnonymousClone(props, forge);
        broken.start();
        assertFalse(broken.available(), "a missing branch logs and serves nothing");
        broken.stop();
    }
}
