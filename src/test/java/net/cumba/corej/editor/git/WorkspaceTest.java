package net.cumba.corej.editor.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.editor.corpus.CorpusLayout;
import net.cumba.corej.editor.error.EditorException;
import net.cumba.corej.editor.testing.RemoteRepo;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The workspace against a local bare remote: every §5.2 and §5.8.3 rule, with its control. */
class WorkspaceTest
{

    @TempDir
    Path tmp;

    private RemoteRepo remote;

    private Path hooks;

    private Workspace ws;

    @BeforeEach
    void setUp() throws IOException, GitAPIException
    {
        remote = RemoteRepo.create(tmp.resolve("remote"));
        hooks = Files.createDirectories(tmp.resolve("hooks-disabled"));
        ws = Workspace.create(tmp.resolve("ws"), remote.url(), RemoteRepo.DEFAULT_BRANCH, null,
                hooks);
    }


    @AfterEach
    void tearDown()
    {
        ws.close();
        remote.close();
    }


    private static EditorException refused(String aCode,
            org.junit.jupiter.api.function.Executable aCall)
    {
        EditorException e = assertThrows(EditorException.class, aCall);
        assertEquals(aCode, e.code(), e.getMessage());
        return e;
    }


    private String content(String aPath) throws IOException
    {
        return new String(ws.read(aPath).orElseThrow(), StandardCharsets.UTF_8);
    }


    private String blob(String aPath) throws IOException
    {
        return Workspace.blobId(ws.read(aPath).orElseThrow());
    }


    private void onFeatureWithOneCommit() throws IOException
    {
        ws.createBranch("feature", null);
        ws.write(RemoteRepo.CHECK_A, "Core:\n  Id: \"CDISC-AD0001\"\nDescription: \"edited\"\n"
                .getBytes(StandardCharsets.UTF_8), blob(RemoteRepo.CHECK_A));
        ws.commit("edit A", "Michael", "mw@example.test");
    }

    // ---- creation and hardening -----------------------------------------------------------


    @Test
    void createClonesTheDefaultBranchWithTheHardeningConfig() throws IOException
    {
        assertEquals("main", ws.currentBranch());
        assertEquals(remote.tip("main"), ws.head());
        assertTrue(Files.isRegularFile(ws.directory().resolve(RemoteRepo.CHECK_A)),
                "the working tree is populated (not the empty-index checkout trap)");
        assertTrue(ws.status().isClean());
        StoredConfig c = Git.open(ws.directory().toFile()).getRepository().getConfig();
        assertFalse(c.getBoolean("core", null, "symlinks", true));
        assertEquals(hooks.toAbsolutePath().toString(), c.getString("core", null, "hooksPath"));
        assertEquals("main", c.getString("branch", "main", "merge").replace("refs/heads/", ""));
        UnpushedInfo u = ws.unpushed("main");
        assertTrue(u.hasTrackingRef());
        assertFalse(u.unpushed());
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a",
                Workspace.blobId("hello\n".getBytes(StandardCharsets.UTF_8)), "blob ids are git's");
    }


    @Test
    void openReassertsTheHardeningAndKeepsTheDefaultBranch() throws IOException
    {
        ws.close();
        try (Git g = Git.open(tmp.resolve("ws").toFile()))
        {
            StoredConfig c = g.getRepository().getConfig();
            c.setBoolean("core", null, "symlinks", true);
            c.unset("core", null, "hooksPath");
            c.save();
        }
        ws = Workspace.open(tmp.resolve("ws"), hooks);
        StoredConfig c = Git.open(ws.directory().toFile()).getRepository().getConfig();
        assertFalse(c.getBoolean("core", null, "symlinks", true));
        assertEquals(hooks.toAbsolutePath().toString(), c.getString("core", null, "hooksPath"));
        assertEquals("main", ws.defaultBranch());
        assertThrows(IOException.class, () -> Workspace.open(remote.seedDir(), hooks),
                "a plain clone is not a workspace");
    }


    @Test
    void createFailsWhenTheRemoteLacksTheDefaultBranch()
    {
        IOException e = assertThrows(IOException.class,
                () -> Workspace.create(tmp.resolve("ws2"), remote.url(), "nope", null, hooks));
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
    }

    // ---- the default branch is read-only ---------------------------------------------------


    @Test
    void writeCommitAndPushOnTheDefaultBranchAreRefused() throws IOException
    {
        refused("DEFAULT_BRANCH",
                () -> ws.write(RemoteRepo.CHECK_A, new byte[0], blob(RemoteRepo.CHECK_A)));
        refused("DEFAULT_BRANCH", () -> ws.commit("x", "a", "a@b"));
        refused("DEFAULT_BRANCH", () -> ws.push(null));
        refused("DEFAULT_BRANCH", () -> ws.updateFrom("main", null));
        refused("DEFAULT_BRANCH", () -> ws.createBranch("main", null));
        assertTrue(ws.status().isClean(), "nothing was written");
    }

    // ---- save / status / commit / push -----------------------------------------------------


    @Test
    void saveCommitPushRoundTripWithTheThreeDirtyStates() throws IOException
    {
        ws.createBranch("feature", null);
        assertEquals("feature", ws.currentBranch());
        assertEquals(remote.tip("main"), ws.head(), "created from the remote default branch");
        UnpushedInfo fresh = ws.unpushed("feature");
        assertFalse(fresh.hasTrackingRef());
        assertTrue(fresh.unpushed(), "a branch the remote lacks counts as unpushed");
        assertEquals(0, fresh.aheadCount());

        String before = blob(RemoteRepo.CHECK_A);
        String blob = ws.write(RemoteRepo.CHECK_A,
                "Core: edited\n".getBytes(StandardCharsets.UTF_8), before);
        assertNotEquals(before, blob);
        assertEquals("Core: edited\n", content(RemoteRepo.CHECK_A));
        String newBlob = ws.write("rules-src/checks/DRAFT/DRAFT-X0001.yaml",
                "Core: new\n".getBytes(StandardCharsets.UTF_8), null);
        assertEquals(Workspace.blobId("Core: new\n".getBytes(StandardCharsets.UTF_8)), newBlob);

        WorkTreeStatus st = ws.status();
        assertEquals(List.of(new ChangedFile(RemoteRepo.CHECK_A, ChangedFile.Kind.MODIFIED),
                new ChangedFile("rules-src/checks/DRAFT/DRAFT-X0001.yaml",
                        ChangedFile.Kind.UNTRACKED)),
                st.allowed());
        assertTrue(st.ignored().isEmpty());

        CommitInfo c = ws.commit("edit A, add X0001", "Michael", "mw@example.test");
        assertEquals("edit A, add X0001", c.message());
        assertEquals(c.sha(), ws.head().name());
        assertTrue(ws.status().isClean());
        UnpushedInfo u = ws.unpushed("feature");
        assertTrue(u.unpushed());
        assertEquals(1, u.aheadCount());
        assertEquals(c.sha(), u.commits().get(0).sha());

        PushOutcome p = ws.push(null);
        assertTrue(p.created());
        assertEquals("OK", p.status());
        assertEquals(c.sha(), remote.tip("feature").name());
        assertEquals(c.sha(), ws.lastPushed("feature"));
        UnpushedInfo after = ws.unpushed("feature");
        assertTrue(after.hasTrackingRef(), "the pushed branch now tracks origin");
        assertFalse(after.unpushed());
        assertEquals("UP_TO_DATE", ws.push(null).status());
    }


    @Test
    void writeIsRefusedOnAStaleOrWrongBlob() throws IOException
    {
        ws.createBranch("feature", null);
        EditorException stale = refused("STALE", () -> ws.write(RemoteRepo.CHECK_A,
                "x".getBytes(StandardCharsets.UTF_8), "0000000000000000000000000000000000000000"));
        assertEquals("feature", stale.details().get("branch"));
        assertEquals(ws.head().name(), stale.details().get("head"));
        refused("STALE",
                () -> ws.write(RemoteRepo.CHECK_A, "x".getBytes(StandardCharsets.UTF_8), null));
        refused("STALE", () -> ws.write("rules-src/checks/DRAFT/new.yaml",
                "x".getBytes(StandardCharsets.UTF_8), blob(RemoteRepo.CHECK_A)));
        assertTrue(ws.status().isClean(), "a refused write leaves no trace");
        refused("NOTHING_TO_COMMIT", () -> ws.commit("nothing", "a", "a@b"));
    }


    @Test
    void requireExpectedDetectsAMovedBranchOrHead() throws IOException
    {
        String head = ws.head().name();
        ws.requireExpected("main", head);
        assertEquals("main", ws.currentBranch(), "the matching expectation passes silently");
        refused("STALE", () -> ws.requireExpected("feature", head));
        refused("STALE",
                () -> ws.requireExpected("main", "0000000000000000000000000000000000000000"));
    }

    // ---- hooks never run -------------------------------------------------------------------


    private static void plantHook(Path aGitDir, String aName, Path aMarker) throws IOException
    {
        Path dir = Files.createDirectories(aGitDir.resolve("hooks"));
        Path hook = dir.resolve(aName);
        Files.writeString(hook, "#!/bin/sh\necho ran > '" + aMarker + "'\nexit 0\n");
        Files.setPosixFilePermissions(hook, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    }


    @Test
    void plantedHooksDoNotRunAndTheControlProvesTheyWould() throws IOException, GitAPIException
    {
        // Control: in a plain clone with the same hooks, JGit runs both hooks.
        Path marker = tmp.resolve("control-pre-commit");
        Path pushMarker = tmp.resolve("control-pre-push");
        plantHook(remote.seedDir().resolve(".git"), "pre-commit", marker);
        plantHook(remote.seedDir().resolve(".git"), "pre-push", pushMarker);
        remote.commitAndPush("main", Map.of("README.md", "# control\n"), "control");
        assertTrue(Files.exists(marker), "control: JGit runs a planted pre-commit hook");
        assertTrue(Files.exists(pushMarker), "control: JGit runs a planted pre-push hook");

        // The workspace: the same hooks planted in .git/hooks, and in the hooksPath directory
        // nothing (the server owns it). Neither commit nor push runs them.
        Path wsMarker = tmp.resolve("ws-pre-commit");
        Path wsPushMarker = tmp.resolve("ws-pre-push");
        plantHook(ws.directory().resolve(".git"), "pre-commit", wsMarker);
        plantHook(ws.directory().resolve(".git"), "pre-push", wsPushMarker);
        plantHook(ws.directory().resolve(".git"), "commit-msg", tmp.resolve("ws-commit-msg"));
        onFeatureWithOneCommit();
        ws.push(null);
        assertFalse(Files.exists(wsMarker), "pre-commit ran in the workspace");
        assertFalse(Files.exists(tmp.resolve("ws-commit-msg")), "commit-msg ran");
        assertFalse(Files.exists(wsPushMarker), "pre-push ran in the workspace");
        assertEquals(ws.head(), remote.tip("feature"));
    }

    // ---- symlinks --------------------------------------------------------------------------


    @Test
    void aCommittedSymlinkIsCheckedOutAsAPlainFileAndAPlantedOneIsRefused()
        throws IOException, GitAPIException
    {
        Path link = remote.seedDir().resolve("rules-src/checks/CDISC/link.yaml");
        Files.createSymbolicLink(link, Path.of("../../../pom.xml"));
        remote.seed().add().addFilepattern("rules-src/checks/CDISC/link.yaml").call();
        remote.commitAndPush("main", Map.of("README.md", "# with a link\n"), "add a symlink");

        try (Workspace fresh = Workspace.create(tmp.resolve("ws-fresh"), remote.url(), "main", null,
                hooks))
        {
            Path checkedOut = fresh.directory().resolve("rules-src/checks/CDISC/link.yaml");
            assertTrue(Files.exists(checkedOut));
            assertFalse(Files.isSymbolicLink(checkedOut), "core.symlinks=false: a plain file");
            assertEquals("../../../pom.xml",
                    new String(fresh.read("rules-src/checks/CDISC/link.yaml").orElseThrow(),
                            StandardCharsets.UTF_8));

            Path planted = fresh.directory().resolve("rules-src/checks/CDISC/evil.yaml");
            Files.createSymbolicLink(planted, fresh.directory().resolve("pom.xml"));
            refused("BAD_PATH", () -> fresh.read("rules-src/checks/CDISC/evil.yaml"));
            assertTrue(fresh.source().list("rules-src/checks/CDISC").stream()
                    .noneMatch(e -> e.name().equals("evil.yaml")), "never listed");
        }
    }

    // ---- branches --------------------------------------------------------------------------


    @Test
    void switchNeedsACleanTreeAndRemoteBranchesBecomeTrackingLocals()
        throws IOException, GitAPIException
    {
        ws.createBranch("feature", null);
        ws.write(RemoteRepo.CHECK_B, "Core: dirty\n".getBytes(StandardCharsets.UTF_8),
                blob(RemoteRepo.CHECK_B));
        EditorException dirty = refused("DIRTY_TREE", () -> ws.switchBranch("main"));
        assertEquals(List.of(RemoteRepo.CHECK_B), dirty.details().get("paths"));
        refused("DIRTY_TREE", () -> ws.createBranch("other", null));
        refused("DIRTY_TREE", () -> ws.updateFrom("main", null));
        assertEquals("feature", ws.currentBranch(), "still on feature");
        ws.commit("keep", "a", "a@b");

        remote.commitAndPush("colleague", Map.of(RemoteRepo.CHECK_F, "Core: theirs\n"),
                "colleague's branch");
        refused("NOT_FOUND", () -> ws.switchBranch("colleague"));
        ws.fetch(null);
        ws.switchBranch("colleague");
        assertEquals("colleague", ws.currentBranch());
        assertEquals("Core: theirs\n", content(RemoteRepo.CHECK_F));
        assertTrue(ws.unpushed("colleague").hasTrackingRef());
        assertFalse(ws.unpushed("colleague").unpushed());
        ws.switchBranch("main");
        assertEquals("main", ws.currentBranch());
        assertTrue(content(RemoteRepo.CHECK_F).startsWith("Core:\n  Id: \"FDA-SD1142\""));

        List<BranchInfo> all = ws.branches();
        assertEquals(List.of("colleague", "feature", "main"), all.stream()
                .filter(b -> b.kind() == BranchInfo.Kind.LOCAL).map(BranchInfo::name).toList());
        assertTrue(all.stream().filter(b -> b.name().equals("main")).findFirst().orElseThrow()
                .isDefault());
        assertTrue(all.stream().filter(b -> b.name().equals("main")).findFirst().orElseThrow()
                .current());
        refused("BRANCH_EXISTS", () -> ws.createBranch("colleague", null));
        refused("BAD_REQUEST", () -> ws.createBranch("bad name", null));
        refused("BAD_REQUEST", () -> ws.createBranch("-x", null));
        refused("BAD_REQUEST", () -> ws.createBranch("a..b", null));
    }


    @Test
    void remoteOnlyBranchesAreListedAsRemote() throws IOException, GitAPIException
    {
        remote.commitAndPush("theirs", Map.of("README.md", "# theirs\n"), "theirs");
        ws.fetch(null);
        BranchInfo theirs = ws.branches().stream().filter(b -> b.name().equals("theirs"))
                .findFirst().orElseThrow();
        assertEquals(BranchInfo.Kind.REMOTE, theirs.kind());
        assertNull(theirs.unpushed());
        assertEquals(remote.tip("theirs").name(), theirs.head());
    }

    // ---- push rejection and update-from-remote --------------------------------------------


    @Test
    void nonFastForwardIsReportedNeverForcedAndUpdateFromRemoteResolvesIt()
        throws IOException, GitAPIException
    {
        onFeatureWithOneCommit();
        ws.push(null);
        // the colleague pushes to the same branch
        remote.commitAndPush("feature", Map.of(RemoteRepo.CHECK_F, "Core: theirs\n"),
                "colleague on feature");
        ObjectId theirs = remote.tip("feature");
        ws.write(RemoteRepo.CHECK_B, "Core: mine\n".getBytes(StandardCharsets.UTF_8),
                blob(RemoteRepo.CHECK_B));
        ws.commit("mine", "a", "a@b");
        refused("NON_FAST_FORWARD", () -> ws.push(null));
        assertEquals(theirs, remote.tip("feature"), "the remote was not forced");

        MergeOutcome m = ws.updateFrom("feature", null);
        assertEquals("MERGED", m.status());
        assertEquals("origin/feature", m.ref());
        assertEquals("Core: theirs\n", content(RemoteRepo.CHECK_F));
        assertEquals("Core: mine\n", content(RemoteRepo.CHECK_B));
        ws.push(null);
        assertEquals(ws.head(), remote.tip("feature"));
        assertFalse(ws.unpushed("feature").unpushed());
    }

    // ---- update from default ---------------------------------------------------------------


    @Test
    void updateFromDefaultFastForwardsAndAbortsOnConflictByResettingToThePreMergeHead()
        throws IOException, GitAPIException
    {
        ws.createBranch("feature", null);
        remote.commitAndPush("main", Map.of(RemoteRepo.CHECK_F, "Core: upstream\n"), "upstream");
        MergeOutcome ff = ws.updateFrom("main", null);
        assertEquals("FAST_FORWARD", ff.status());
        assertEquals("Core: upstream\n", content(RemoteRepo.CHECK_F));
        assertEquals("ALREADY_UP_TO_DATE", ws.updateFrom("main", null).status());

        ws.write(RemoteRepo.CHECK_A, "Core: mine\n".getBytes(StandardCharsets.UTF_8),
                blob(RemoteRepo.CHECK_A));
        ws.commit("mine", "a", "a@b");
        ObjectId pre = ws.head();
        remote.commitAndPush("main", Map.of(RemoteRepo.CHECK_A, "Core: theirs\n"), "theirs");
        EditorException conflict = refused("MERGE_CONFLICT", () -> ws.updateFrom("main", null));
        assertEquals(List.of(RemoteRepo.CHECK_A), conflict.details().get("paths"));
        assertEquals(pre.name(), conflict.details().get("head"));
        assertEquals(pre, ws.head(), "reset to the pre-merge HEAD");
        assertTrue(ws.status().isClean(), "no conflict markers left behind");
        assertEquals("Core: mine\n", content(RemoteRepo.CHECK_A));
        refused("NOT_FOUND", () -> ws.updateFrom("no-such-branch", null));
    }

    // ---- delete merged branch --------------------------------------------------------------


    @Test
    void deleteMergedBranchWorksAfterTheForgeDeletedTheRemoteOne()
        throws IOException, GitAPIException
    {
        onFeatureWithOneCommit();
        ws.push(null);
        refused("CURRENT_BRANCH", () -> ws.deleteBranch("feature"));
        ws.switchBranch("main");
        remote.deleteRemoteBranch("feature");
        assertTrue(ws.unpushed("feature").hasTrackingRef(), "before the prune");
        ws.fetch(null);
        UnpushedInfo u = ws.unpushed("feature");
        assertFalse(u.hasTrackingRef(), "the tracking ref was pruned");
        assertFalse(u.unpushed(), "HEAD is the last pushed SHA: not unpushed forever (M5)");
        ws.deleteBranch("feature");
        assertTrue(ws.branches().stream().noneMatch(b -> b.name().equals("feature")));
        assertNull(ws.lastPushed("feature"));
        refused("NOT_FOUND", () -> ws.deleteBranch("feature"));
    }


    @Test
    void deleteRefusesABranchWithUnpushedWork() throws IOException
    {
        onFeatureWithOneCommit();
        ws.switchBranch("main");
        refused("UNPUSHED", () -> ws.deleteBranch("feature"));
        assertTrue(ws.branches().stream().anyMatch(b -> b.name().equals("feature")));
    }

    // ---- discard ---------------------------------------------------------------------------

    private static final String NEW_CHECK = "rules-src/checks/NEWFAM/NEWFAM-X0001.yaml";

    private static final String NEW_SCENARIO = CorpusLayout.SCENARIOS
            + "/newfam/NEWFAM-X0001/valid.cdt";

    private static final String NEW_DOCS = "rules-src/docs/CDISC/CDISC-ZZ0001.yaml";

    private ObjectId localTip(String aBranch) throws IOException
    {
        try (Git g = Git.open(ws.directory().toFile()))
        {
            return g.getRepository().resolve("refs/heads/" + aBranch);
        }
    }


    /**
     * The paths of the current uncommitted changes under the allow-list, as the dialog lists them.
     */
    private List<String> confirmed()
    {
        return ws.status().allowed().stream().map(ChangedFile::path).toList();
    }


    @Test
    void discardRestoresTrackedFilesDeletesNewOnesAndKeepsCommitsBranchesAndTheRest()
        throws IOException, GitAPIException
    {
        onFeatureWithOneCommit();
        String committedA = content(RemoteRepo.CHECK_A);
        ObjectId head = ws.head();
        ObjectId mainTip = localTip("main");
        String originalB = content(RemoteRepo.CHECK_B);
        String originalF = content(RemoteRepo.CHECK_F);
        Path outside = Files.writeString(tmp.resolve("outside-the-workspace.txt"), "keep\n");

        ws.write(RemoteRepo.CHECK_B, "Core: dirty\n".getBytes(StandardCharsets.UTF_8),
                blob(RemoteRepo.CHECK_B));
        Files.delete(ws.directory().resolve(RemoteRepo.CHECK_F));
        ws.write(NEW_CHECK, "Core: new\n".getBytes(StandardCharsets.UTF_8), null);
        ws.write(NEW_SCENARIO, "#!RuleTest\n".getBytes(StandardCharsets.UTF_8), null);
        ws.write(NEW_DOCS, "Core: docs\n".getBytes(StandardCharsets.UTF_8), null);
        try (Git g = Git.open(ws.directory().toFile()))
        {
            g.add().addFilepattern(NEW_DOCS).call(); // ADDED: a commit that failed after staging
        }
        Files.writeString(ws.directory().resolve("README.md"), "# outside the allow-list\n");
        assertEquals(
                List.of(new ChangedFile(RemoteRepo.CHECK_B, ChangedFile.Kind.MODIFIED),
                        new ChangedFile(RemoteRepo.CHECK_F, ChangedFile.Kind.DELETED),
                        new ChangedFile(NEW_CHECK, ChangedFile.Kind.UNTRACKED),
                        new ChangedFile(NEW_DOCS, ChangedFile.Kind.ADDED),
                        new ChangedFile(NEW_SCENARIO, ChangedFile.Kind.UNTRACKED)),
                ws.status().allowed(), "in path order, every kind the editor can leave behind");

        List<ChangedFile> discarded = ws.discard(confirmed());

        assertEquals(5, discarded.size());
        WorkTreeStatus after = ws.status();
        assertTrue(after.allowed().isEmpty(), "every allowed change is gone: " + after.allowed());
        assertEquals(List.of(new ChangedFile("README.md", ChangedFile.Kind.MODIFIED)),
                after.ignored(), "a change outside the allow-list is left alone");
        assertEquals(originalB, content(RemoteRepo.CHECK_B), "restored from HEAD");
        assertEquals(originalF, content(RemoteRepo.CHECK_F), "a deleted file comes back");
        assertEquals(committedA, content(RemoteRepo.CHECK_A), "the commit is kept");
        assertTrue(ws.read(NEW_CHECK).isEmpty());
        assertTrue(ws.read(NEW_DOCS).isEmpty());
        assertTrue(ws.read(NEW_SCENARIO).isEmpty());
        assertFalse(Files.exists(ws.directory().resolve("rules-src/checks/NEWFAM")),
                "the directory the new rule created is removed");
        assertFalse(Files.exists(ws.directory().resolve(CorpusLayout.SCENARIOS + "/newfam")),
                "and the scenario's, up to the allow-list root");
        assertTrue(Files.isDirectory(ws.directory().resolve(CorpusLayout.SCENARIOS)),
                "an allow-list root itself stays");
        assertTrue(Files.isDirectory(ws.directory().resolve("rules-src/docs/CDISC")),
                "a directory still holding tracked files stays");
        assertEquals("feature", ws.currentBranch());
        assertEquals(head, ws.head(), "HEAD does not move");
        assertEquals(mainTip, localTip("main"), "other branches are untouched");
        assertEquals("keep\n", Files.readString(outside), "nothing outside the clone is touched");
        assertTrue(ws.unpushed("feature").unpushed(), "the unpushed commit is still there");
    }


    @Test
    void discardRefusesAConfirmedSetThatIsNotTheCurrentOneAndChangesNothing() throws IOException
    {
        ws.createBranch("feature", null);
        ws.write(RemoteRepo.CHECK_B, "Core: dirty\n".getBytes(StandardCharsets.UTF_8),
                blob(RemoteRepo.CHECK_B));
        for (List<String> wrong : List.of(List.<String> of(),
                List.of(RemoteRepo.CHECK_B, RemoteRepo.CHECK_A),
                List.of(RemoteRepo.CHECK_B, "rules-src/checks/../../pom.xml"),
                List.of(".git/config"), List.of("README.md")))
        {
            EditorException stale = refused("STALE", () -> ws.discard(wrong));
            assertEquals(List.of(RemoteRepo.CHECK_B), stale.details().get("paths"),
                    String.valueOf(wrong));
            assertEquals("feature", stale.details().get("branch"));
        }
        assertEquals("Core: dirty\n", content(RemoteRepo.CHECK_B),
                "a refused discard changes nothing");
        assertEquals(List.of(new ChangedFile(RemoteRepo.CHECK_B, ChangedFile.Kind.MODIFIED)),
                ws.discard(List.of(RemoteRepo.CHECK_B, RemoteRepo.CHECK_B)),
                "the confirmed list is a set");
        assertTrue(ws.status().isClean());
        assertEquals(List.of(), ws.discard(List.of()), "a clean tree discards nothing");
    }


    @Test
    void discardRefusesAPlantedSymlinkBeforeChangingAnything() throws IOException
    {
        ws.createBranch("feature", null);
        ws.write(RemoteRepo.CHECK_B, "Core: dirty\n".getBytes(StandardCharsets.UTF_8),
                blob(RemoteRepo.CHECK_B));
        Path outsideDir = Files.createDirectories(tmp.resolve("outside"));
        Path secret = Files.writeString(outsideDir.resolve("secret.yaml"), "secret\n");
        Files.createSymbolicLink(ws.directory().resolve("rules-src/checks/CDISC/evil.yaml"),
                secret);
        Files.createSymbolicLink(ws.directory().resolve("rules-src/checks/LINKED"), outsideDir);
        List<String> paths = confirmed();
        assertTrue(paths.contains("rules-src/checks/CDISC/evil.yaml"), String.valueOf(paths));

        refused("BAD_PATH", () -> ws.discard(paths));

        assertEquals("Core: dirty\n", content(RemoteRepo.CHECK_B),
                "validated before the first change: nothing was restored");
        assertTrue(
                Files.isSymbolicLink(ws.directory().resolve("rules-src/checks/CDISC/evil.yaml")));
        assertEquals("secret\n", Files.readString(secret), "the link's target is untouched");
        assertTrue(Files.exists(outsideDir.resolve("secret.yaml")));
    }


    @Test
    void identityAndLastUsedAreRecorded() throws IOException
    {
        ws.setIdentity("Michael", "mw@example.test");
        StoredConfig c = Git.open(ws.directory().toFile()).getRepository().getConfig();
        assertEquals("Michael", c.getString("user", null, "name"));
        assertTrue(ws.lastUsed().isAfter(java.time.Instant.EPOCH));
        assertFalse(ws.status().paths().contains(Workspace.LAST_USED_MARKER),
                "nothing the editor keeps shows in git status");
    }
}
