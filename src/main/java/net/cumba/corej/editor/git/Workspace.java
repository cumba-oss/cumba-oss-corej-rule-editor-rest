package net.cumba.corej.editor.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import net.cumba.corej.editor.error.EditorException;
import net.cumba.corej.editor.error.Errors;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;

/**
 * One user's clone of the bound repository (§5.1.5) and every git operation the editor performs on
 * it (§5.2), under the §5.8 rules:
 * <ul>
 * <li>created with {@code core.symlinks=false} and {@code core.hooksPath} pointing at an empty,
 * server-owned directory (§5.8.3). JGit runs repository hooks otherwise, so a planted
 * {@code .git/hooks/pre-commit} would run as the server. ⚠ {@code core.hooksPath} is the control
 * that holds: commits also pass {@code --no-verify}, but that skips only {@code pre-commit} and
 * {@code commit-msg} — JGit still runs {@code post-commit} (and {@code pre-push} on a push) from
 * the hooks directory (security review INFO; WorkspaceTest plants all four);</li>
 * <li>the default branch is read-only: file writes, commits and pushes on it are refused (§5.2,
 * review M2), compared case-insensitively (a case-insensitive file system aliases {@code MAIN} to
 * {@code main}). ⚠ This is a convenience of the editor, not the barrier: the name is frozen at
 * clone, so the FORGE's branch protection on the default branch is what keeps it safe (security
 * review L7);</li>
 * <li>every branch name a request carries is a valid ref name not starting with {@code -} (security
 * review L6), before JGit sees it;</li>
 * <li>branch switch and update-from-* need a clean tree (§5.2, review H4); a conflicting merge is
 * aborted by resetting to the pre-merge HEAD;</li>
 * <li>a push is never forced; non-fast-forward is reported (§5.2);</li>
 * <li>the last pushed SHA per branch is recorded in {@code .git/config} (§5.2, review M5), so
 * nothing the editor keeps ever shows up in {@code git status};</li>
 * <li>every operation, reads included, runs under the workspace {@link #lock() lock} (§5.8.4);</li>
 * <li>every transport (clone, fetch, push) has a timeout (security review L11).</li>
 * </ul>
 */
public final class Workspace implements AutoCloseable
{

    static final String ORIGIN = "origin";

    /** {@code [cumba-editor] default-branch = <name>} in the clone's config. */
    static final String EDITOR_SECTION = "cumba-editor";

    static final String DEFAULT_BRANCH_KEY = "default-branch";

    /** {@code [cumba-editor-pushed "<branch>"] sha = <id>}: the push ledger. */
    static final String PUSHED_SECTION = "cumba-editor-pushed";

    static final String PUSHED_SHA_KEY = "sha";

    /** A marker under {@code .git/} whose mtime is the workspace's last use (retention). */
    static final String LAST_USED_MARKER = "cumba-editor-last-used";

    /** The transport timeout when none is configured (seconds, JGit's unit). */
    public static final int DEFAULT_GIT_TIMEOUT_SECONDS = 60;

    private final Path dir;

    private final Git git;

    private final Repository repo;

    private final String defaultBranch;

    private final ReentrantLock lock = new ReentrantLock(true);

    private final WorkingTreeSource source;

    private final int gitTimeoutSeconds;

    private volatile boolean retired;

    private Workspace(Path aDir, Git aGit, String aDefaultBranch, int aGitTimeoutSeconds)
    {
        dir = aDir;
        git = aGit;
        repo = aGit.getRepository();
        defaultBranch = aDefaultBranch;
        source = new WorkingTreeSource(aDir);
        gitTimeoutSeconds = aGitTimeoutSeconds;
    }


    /** Whether a workspace clone exists at the directory. */
    public static boolean exists(Path aDir)
    {
        return Files.isDirectory(aDir.resolve(Constants.DOT_GIT));
    }


    /**
     * Clones the repository into a new workspace: {@code init}, the hardening config, a fetch of
     * every branch, the default branch created tracking its remote, then a hard reset onto it.
     *
     * <p>
     * ⚠ Not {@code checkout -b} after the fetch: with HEAD already pointing at the (unborn) branch
     * of that name, JGit's two-tree checkout sees head == merge for every path and KEEPS the empty
     * index, leaving the working tree empty. The one-tree checkout of a hard reset populates it
     * unconditionally.
     * </p>
     *
     * @param aCredentials
     *            the user's token; null for a credential-free transport (tests use {@code file://})
     */
    public static Workspace create(Path aDir, String aUrl, String aDefaultBranch,
            @Nullable CredentialsProvider aCredentials, Path aHooksDir)
        throws IOException
    {
        return create(aDir, aUrl, aDefaultBranch, aCredentials, aHooksDir,
                DEFAULT_GIT_TIMEOUT_SECONDS);
    }


    /**
     * {@link #create(Path, String, String, CredentialsProvider, Path)} with a transport timeout.
     *
     * @param aGitTimeoutSeconds
     *            the timeout of every fetch and push of this workspace (security review L11)
     */
    @SuppressWarnings("PMD.CloseResource") // owned by the returned Workspace, closed on failure
    public static Workspace create(Path aDir, String aUrl, String aDefaultBranch,
            @Nullable CredentialsProvider aCredentials, Path aHooksDir, int aGitTimeoutSeconds)
        throws IOException
    {
        Files.createDirectories(aDir);
        Git g = null;
        try
        {
            g = Git.init().setDirectory(aDir.toFile()).setInitialBranch(aDefaultBranch).call();
            StoredConfig c = g.getRepository().getConfig();
            harden(c, aHooksDir);
            c.setString("remote", ORIGIN, "url", aUrl);
            c.setString("remote", ORIGIN, "fetch", "+refs/heads/*:refs/remotes/" + ORIGIN + "/*");
            c.setString(EDITOR_SECTION, null, DEFAULT_BRANCH_KEY, aDefaultBranch);
            c.save();
            g.fetch().setRemote(ORIGIN).setCredentialsProvider(aCredentials)
                    .setRemoveDeletedRefs(true).setTimeout(aGitTimeoutSeconds).call();
            if (g.getRepository().exactRef(remoteRef(aDefaultBranch)) == null)
            {
                throw new IOException("the remote has no branch '" + aDefaultBranch + "'");
            }
            g.branchCreate().setName(aDefaultBranch).setStartPoint(ORIGIN + "/" + aDefaultBranch)
                    .setUpstreamMode(SetupUpstreamMode.TRACK).call();
            g.reset().setMode(ResetType.HARD).setRef(Constants.R_HEADS + aDefaultBranch).call();
            Workspace ws = new Workspace(aDir, g, aDefaultBranch, aGitTimeoutSeconds);
            ws.touch();
            return ws;
        }
        catch (IOException e)
        {
            closeQuietly(g);
            throw e;
        }
        catch (GitAPIException | RuntimeException e)
        {
            closeQuietly(g);
            throw new IOException("cloning " + aUrl + " failed: " + e.getMessage(), e);
        }
    }


    private static void closeQuietly(@Nullable Git aGit)
    {
        if (aGit != null)
        {
            aGit.close();
        }
    }


    /** Opens an existing workspace, re-asserting the hardening config. */
    public static Workspace open(Path aDir, Path aHooksDir) throws IOException
    {
        return open(aDir, aHooksDir, DEFAULT_GIT_TIMEOUT_SECONDS);
    }


    /** {@link #open(Path, Path)} with a transport timeout (security review L11). */
    public static Workspace open(Path aDir, Path aHooksDir, int aGitTimeoutSeconds)
        throws IOException
    {
        Git g = Git.open(aDir.toFile());
        try
        {
            StoredConfig c = g.getRepository().getConfig();
            String def = c.getString(EDITOR_SECTION, null, DEFAULT_BRANCH_KEY);
            if (def == null || def.isBlank())
            {
                throw new IOException(aDir.getFileName() + " is not an editor workspace (no "
                        + EDITOR_SECTION + "." + DEFAULT_BRANCH_KEY + ")");
            }
            harden(c, aHooksDir);
            c.save();
            return new Workspace(aDir, g, def, aGitTimeoutSeconds);
        }
        catch (IOException | RuntimeException e)
        {
            g.close();
            throw e;
        }
    }


    private static void harden(StoredConfig aConfig, Path aHooksDir)
    {
        aConfig.setBoolean("core", null, "symlinks", false);
        aConfig.setString("core", null, "hooksPath",
                aHooksDir.toAbsolutePath().normalize().toString());
    }


    static String remoteRef(String aBranch)
    {
        return Constants.R_REMOTES + ORIGIN + "/" + aBranch;
    }


    public Path directory()
    {
        return dir;
    }


    public String defaultBranch()
    {
        return defaultBranch;
    }


    /** The lock every request holds while it touches this workspace (§5.8.4). */
    public ReentrantLock lock()
    {
        return lock;
    }


    /** The working tree as a {@link CorpusSource}. */
    public CorpusSource source()
    {
        return source;
    }


    /**
     * The HEAD commit as an immutable {@link CorpusSource}: unlike the working tree it may be read
     * after the lock is released — a later commit or checkout moves HEAD, never this snapshot (REST
     * review #5: the corpus index is built from it outside the lock).
     */
    public SnapshotSource headSnapshot() throws IOException
    {
        return new SnapshotSource(repo, head());
    }


    /**
     * Marks the workspace as removed (retention, security review L10): a request that obtained it
     * before the deletion and was waiting for its lock must not use it.
     */
    void retire()
    {
        retired = true;
    }


    /** Whether the workspace was removed since it was handed out. */
    public boolean isRetired()
    {
        return retired;
    }


    /** Records a use for the retention sweep. */
    public void touch() throws IOException
    {
        Path marker = repo.getDirectory().toPath().resolve(LAST_USED_MARKER);
        Files.writeString(marker, Instant.now().toString());
    }


    /** When the workspace was last used, by the marker's mtime (the directory's when absent). */
    public Instant lastUsed() throws IOException
    {
        Path marker = repo.getDirectory().toPath().resolve(LAST_USED_MARKER);
        Path probe = Files.exists(marker) ? marker : dir;
        return Files.getLastModifiedTime(probe).toInstant();
    }


    /**
     * Marks the workspace used by a session: the commit identity (§5.1.7) and the last-use marker
     * (retention). Both write files — {@code .git/config} and the marker — so only the holder of
     * the {@link #lock() lock} may do it; a push saving the config at the same time would otherwise
     * race it.
     *
     * @throws IllegalStateException
     *             when the calling thread does not hold the lock
     */
    public void markUsedBy(String aName, String aEmail) throws IOException
    {
        if (!lock.isHeldByCurrentThread())
        {
            throw new IllegalStateException("markUsedBy needs the workspace lock");
        }
        setIdentity(aName, aEmail);
        touch();
    }


    /** Sets the commit identity (§5.1.7) merges and commits use. */
    public void setIdentity(String aName, String aEmail) throws IOException
    {
        StoredConfig c = repo.getConfig();
        if (!aName.equals(c.getString("user", null, "name"))
                || !aEmail.equals(c.getString("user", null, "email")))
        {
            c.setString("user", null, "name", aName);
            c.setString("user", null, "email", aEmail);
            c.save();
        }
    }

    // ---- state ---------------------------------------------------------------------------


    public String currentBranch() throws IOException
    {
        String b = repo.getBranch();
        if (b == null)
        {
            throw new IOException("the workspace has no current branch");
        }
        return b;
    }


    public ObjectId head() throws IOException
    {
        ObjectId id = repo.resolve(Constants.HEAD);
        if (id == null)
        {
            throw new IOException("the workspace has no HEAD commit");
        }
        return id;
    }


    /**
     * Whether a branch is the default branch — case-insensitively, since a case-insensitive file
     * system resolves {@code refs/heads/MAIN} to the same ref file as {@code main} (security review
     * L7).
     */
    public boolean isDefaultBranch(String aBranch)
    {
        return defaultBranch.equalsIgnoreCase(aBranch);
    }


    /**
     * The check every branch name from a request passes before JGit sees it (security review L6): a
     * valid ref name under {@code refs/heads/} that does not start with {@code -}. JGit's own
     * lookups would mostly refuse the rest by accident; this refuses it on purpose.
     *
     * @throws EditorException
     *             400 {@code BAD_REQUEST}
     */
    public static String requireBranchName(String aName)
    {
        if (aName.isEmpty() || aName.startsWith("-")
                || !Repository.isValidRefName(Constants.R_HEADS + aName))
        {
            throw Errors.badRequest("'" + aName + "' is not a valid branch name");
        }
        return aName;
    }


    /** The uncommitted state, split by the allow-list. */
    public WorkTreeStatus status()
    {
        Status s;
        try
        {
            s = git.status().call();
        }
        catch (GitAPIException e)
        {
            throw Errors.git("git status failed", e);
        }
        Map<String, ChangedFile.Kind> kinds = new TreeMap<>();
        s.getUntracked().forEach(p -> kinds.put(p, ChangedFile.Kind.UNTRACKED));
        s.getAdded().forEach(p -> kinds.put(p, ChangedFile.Kind.ADDED));
        s.getModified().forEach(p -> kinds.put(p, ChangedFile.Kind.MODIFIED));
        s.getChanged().forEach(p -> kinds.put(p, ChangedFile.Kind.MODIFIED));
        s.getMissing().forEach(p -> kinds.put(p, ChangedFile.Kind.DELETED));
        s.getRemoved().forEach(p -> kinds.put(p, ChangedFile.Kind.DELETED));
        s.getConflicting().forEach(p -> kinds.put(p, ChangedFile.Kind.CONFLICTING));
        List<ChangedFile> allowed = new ArrayList<>();
        List<ChangedFile> ignored = new ArrayList<>();
        for (Map.Entry<String, ChangedFile.Kind> e : kinds.entrySet())
        {
            ChangedFile f = new ChangedFile(e.getKey(), e.getValue());
            (PathPolicy.isAllowed(e.getKey()) ? allowed : ignored).add(f);
        }
        return new WorkTreeStatus(allowed, ignored);
    }


    private void requireClean()
    {
        WorkTreeStatus st = status();
        if (!st.isClean())
        {
            throw Errors.dirtyTree(st.paths());
        }
    }


    private void requireNotDefault(String aBranch)
    {
        if (isDefaultBranch(aBranch))
        {
            throw Errors.defaultBranch(aBranch);
        }
    }


    /**
     * The §5.8.4 check: the client's {@code {branch, head}} must still be the workspace's.
     *
     * @throws EditorException
     *             409 {@code STALE}
     */
    public void requireExpected(String aBranch, String aHead) throws IOException
    {
        String branch = currentBranch();
        String head = head().name();
        if (!branch.equals(aBranch))
        {
            throw Errors.stale("the current branch", branch, head);
        }
        if (!head.equals(aHead))
        {
            throw Errors.stale("HEAD", branch, head);
        }
    }


    /** Push status of a local branch (review M5). */
    public UnpushedInfo unpushed(String aBranch) throws IOException
    {
        ObjectId head = repo.resolve(Constants.R_HEADS + aBranch);
        if (head == null)
        {
            throw Errors.notFound("branch '" + aBranch + "'");
        }
        BranchTrackingStatus tracking = BranchTrackingStatus.of(repo, aBranch);
        if (tracking != null)
        {
            ObjectId remote = repo.resolve(tracking.getRemoteTrackingBranch());
            List<CommitInfo> commits = remote == null ? List.of() : commitsBetween(remote, head);
            return new UnpushedInfo(tracking.getAheadCount() > 0, true, tracking.getAheadCount(),
                    tracking.getBehindCount(), commits);
        }
        String pushed = lastPushed(aBranch);
        boolean unpushed = pushed == null || !pushed.equals(head.name());
        ObjectId base = pushed == null ? repo.resolve(remoteRef(defaultBranch))
                : ObjectId.fromString(pushed);
        List<CommitInfo> commits = base == null || !repo.getObjectDatabase().has(base) ? List.of()
                : commitsBetween(base, head);
        return new UnpushedInfo(unpushed, false, commits.size(), 0, commits);
    }


    private List<CommitInfo> commitsBetween(ObjectId aBase, ObjectId aHead) throws IOException
    {
        List<CommitInfo> commits = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo))
        {
            walk.markStart(walk.parseCommit(aHead));
            walk.markUninteresting(walk.parseCommit(aBase));
            for (RevCommit c : walk)
            {
                commits.add(new CommitInfo(c.getName(), c.getShortMessage(),
                        c.getAuthorIdent().getName()));
            }
        }
        return commits;
    }


    /** The last SHA this server pushed for the branch, from the ledger; null when never. */
    public @Nullable String lastPushed(String aBranch)
    {
        return repo.getConfig().getString(PUSHED_SECTION, aBranch, PUSHED_SHA_KEY);
    }


    private void recordPushed(String aBranch, ObjectId aHead) throws IOException
    {
        StoredConfig c = repo.getConfig();
        c.setString(PUSHED_SECTION, aBranch, PUSHED_SHA_KEY, aHead.name());
        c.save();
    }


    /** The picker's view: local branches with push status, then remote-only ones. */
    public List<BranchInfo> branches() throws IOException
    {
        String current = currentBranch();
        List<BranchInfo> out = new ArrayList<>();
        List<Ref> locals = repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS);
        for (Ref r : locals)
        {
            String name = r.getName().substring(Constants.R_HEADS.length());
            ObjectId id = r.getObjectId();
            out.add(new BranchInfo(name, BranchInfo.Kind.LOCAL, id == null ? "" : id.name(),
                    name.equals(current), isDefaultBranch(name), unpushed(name)));
        }
        String prefix = Constants.R_REMOTES + ORIGIN + "/";
        for (Ref r : repo.getRefDatabase().getRefsByPrefix(prefix))
        {
            String name = r.getName().substring(prefix.length());
            if (Constants.HEAD.equals(name) || repo.exactRef(Constants.R_HEADS + name) != null)
            {
                continue;
            }
            ObjectId id = r.getObjectId();
            out.add(new BranchInfo(name, BranchInfo.Kind.REMOTE, id == null ? "" : id.name(), false,
                    isDefaultBranch(name), null));
        }
        return out;
    }

    // ---- network -------------------------------------------------------------------------


    /** {@code git fetch --prune origin}. */
    public void fetch(@Nullable CredentialsProvider aCredentials)
    {
        try
        {
            git.fetch().setRemote(ORIGIN).setCredentialsProvider(aCredentials)
                    .setRemoveDeletedRefs(true).setTimeout(gitTimeoutSeconds).call();
        }
        catch (TransportException e)
        {
            throw transport("fetch", e);
        }
        catch (GitAPIException e)
        {
            throw Errors.git("git fetch failed", e);
        }
    }


    private static EditorException transport(String aWhat, TransportException aCause)
    {
        EditorException e = Errors
                .forgeUnavailable("git " + aWhat + " failed: " + aCause.getMessage());
        e.initCause(aCause);
        return e;
    }


    /**
     * Pushes the current branch to {@code origin}, never forced. Afterwards the branch tracks its
     * remote counterpart and the ledger records the pushed SHA.
     *
     * @throws EditorException
     *             409 {@code DEFAULT_BRANCH}, 409 {@code NON_FAST_FORWARD}, 502 on transport
     *             failure
     */
    public PushOutcome push(@Nullable CredentialsProvider aCredentials) throws IOException
    {
        String branch = currentBranch();
        requireNotDefault(branch);
        ObjectId head = head();
        boolean created = repo.exactRef(remoteRef(branch)) == null;
        String refName = Constants.R_HEADS + branch;
        Iterable<PushResult> results;
        try
        {
            results = git.push().setRemote(ORIGIN).setRefSpecs(new RefSpec(refName + ":" + refName))
                    .setCredentialsProvider(aCredentials).setForce(false)
                    .setTimeout(gitTimeoutSeconds).call();
        }
        catch (TransportException e)
        {
            throw transport("push", e);
        }
        catch (GitAPIException e)
        {
            throw Errors.git("git push failed", e);
        }
        String status = "";
        for (PushResult r : results)
        {
            RemoteRefUpdate u = r.getRemoteUpdate(refName);
            if (u == null)
            {
                continue;
            }
            status = switch (u.getStatus())
            {
            case OK, UP_TO_DATE -> u.getStatus().name();
            case REJECTED_NONFASTFORWARD -> throw Errors.nonFastForward(branch);
            default -> throw Errors.git("push of " + branch + " rejected: " + u.getStatus(),
                    new IllegalStateException(String.valueOf(u.getMessage())));
            };
        }
        if (status.isEmpty())
        {
            throw Errors.git("push of " + branch + " reported no result",
                    new IllegalStateException("no RemoteRefUpdate for " + refName));
        }
        StoredConfig c = repo.getConfig();
        c.setString("branch", branch, "remote", ORIGIN);
        c.setString("branch", branch, "merge", refName);
        c.save();
        recordPushed(branch, head);
        return new PushOutcome(branch, head.name(), status, created);
    }

    // ---- branches ------------------------------------------------------------------------


    /** Creates a branch from the (freshly fetched) remote default branch and checks it out. */
    public void createBranch(String aName, @Nullable CredentialsProvider aCredentials)
        throws IOException
    {
        requireBranchName(aName);
        requireNotDefault(aName);
        requireClean();
        fetch(aCredentials);
        if (repo.exactRef(Constants.R_HEADS + aName) != null
                || repo.exactRef(remoteRef(aName)) != null)
        {
            throw Errors.branchExists(aName);
        }
        try
        {
            git.checkout().setCreateBranch(true).setName(aName)
                    .setStartPoint(ORIGIN + "/" + defaultBranch)
                    .setUpstreamMode(SetupUpstreamMode.NOTRACK).call();
        }
        catch (GitAPIException e)
        {
            throw Errors.git("creating branch " + aName + " failed", e);
        }
    }


    /** Checks out a local branch, or a remote branch as a new tracking local one. */
    public void switchBranch(String aName) throws IOException
    {
        requireBranchName(aName);
        requireClean();
        try
        {
            if (repo.exactRef(Constants.R_HEADS + aName) != null)
            {
                git.checkout().setName(aName).call();
            }
            else if (repo.exactRef(remoteRef(aName)) != null)
            {
                git.checkout().setCreateBranch(true).setName(aName)
                        .setStartPoint(ORIGIN + "/" + aName)
                        .setUpstreamMode(SetupUpstreamMode.TRACK).call();
            }
            else
            {
                throw Errors.notFound("branch '" + aName + "'");
            }
        }
        catch (GitAPIException e)
        {
            throw Errors.git("switching to " + aName + " failed", e);
        }
    }


    /** Deletes a local branch that holds nothing unpushed ("delete merged branch", §5.2). */
    public void deleteBranch(String aName) throws IOException
    {
        requireBranchName(aName);
        if (aName.equals(currentBranch()))
        {
            throw Errors.currentBranch(aName);
        }
        if (repo.exactRef(Constants.R_HEADS + aName) == null)
        {
            throw Errors.notFound("branch '" + aName + "'");
        }
        if (unpushed(aName).unpushed())
        {
            throw Errors.unpushed(aName);
        }
        try
        {
            git.branchDelete().setBranchNames(aName).setForce(true).call();
        }
        catch (GitAPIException e)
        {
            throw Errors.git("deleting branch " + aName + " failed", e);
        }
        StoredConfig c = repo.getConfig();
        c.unsetSection(PUSHED_SECTION, aName);
        c.unsetSection("branch", aName);
        c.save();
    }


    /**
     * Fetches and merges {@code origin/<aRemoteBranch>} into the current branch (update from
     * default / from the remote branch). A conflict or failure resets to the pre-merge HEAD — safe
     * only because the tree was clean — and is reported with the paths.
     */
    public MergeOutcome updateFrom(String aRemoteBranch, @Nullable CredentialsProvider aCredentials)
        throws IOException
    {
        requireBranchName(aRemoteBranch);
        String current = currentBranch();
        requireNotDefault(current);
        requireClean();
        fetch(aCredentials);
        Ref ref = repo.exactRef(remoteRef(aRemoteBranch));
        if (ref == null)
        {
            throw Errors.notFound("remote branch '" + aRemoteBranch + "'");
        }
        String refLabel = ORIGIN + "/" + aRemoteBranch;
        ObjectId pre = head();
        MergeResult result;
        try
        {
            result = git.merge().include(ref).setCommit(true)
                    .setMessage("Merge " + refLabel + " into " + current).call();
        }
        catch (GitAPIException e)
        {
            resetHard(pre);
            throw Errors.git("merging " + refLabel + " failed", e);
        }
        MergeResult.MergeStatus status = result.getMergeStatus();
        if (status.isSuccessful())
        {
            return new MergeOutcome(refLabel, status.name(), head().name());
        }
        List<String> paths = new ArrayList<>();
        if (result.getConflicts() != null)
        {
            paths.addAll(result.getConflicts().keySet());
        }
        if (result.getFailingPaths() != null)
        {
            paths.addAll(result.getFailingPaths().keySet());
        }
        resetHard(pre);
        throw Errors.mergeConflict(refLabel, paths, pre.name());
    }


    private void resetHard(ObjectId aCommit)
    {
        try
        {
            git.reset().setMode(ResetType.HARD).setRef(aCommit.name()).call();
        }
        catch (GitAPIException e)
        {
            throw Errors.git("resetting to " + aCommit.name() + " failed", e);
        }
    }

    // ---- files ---------------------------------------------------------------------------


    /** The git blob id of a content, the {@code blob} of the optimistic-concurrency triple. */
    public static String blobId(byte[] aContent)
    {
        try (ObjectInserter.Formatter f = new ObjectInserter.Formatter())
        {
            return f.idFor(Constants.OBJ_BLOB, aContent).name();
        }
    }


    /** Reads a file under the allow-list; empty when absent. */
    public Optional<byte[]> read(String aCheckedPath) throws IOException
    {
        return source.read(aCheckedPath);
    }


    /**
     * Writes a file under the allow-list (never on the default branch) if the client's {@code blob}
     * is still the file's: null means "I expect no file".
     *
     * @return the new blob id
     * @throws EditorException
     *             409 {@code STALE} on a blob mismatch, 409 {@code DEFAULT_BRANCH}
     */
    public String write(String aCheckedPath, byte[] aContent, @Nullable String aExpectedBlob)
        throws IOException
    {
        String branch = currentBranch();
        requireNotDefault(branch);
        Path target = PathPolicy.resolve(dir, aCheckedPath);
        Optional<byte[]> existing = source.read(aCheckedPath);
        String currentBlob = existing.map(Workspace::blobId).orElse(null);
        if (currentBlob == null ? aExpectedBlob != null : !currentBlob.equals(aExpectedBlob))
        {
            throw Errors.stale("the file '" + aCheckedPath + "'", branch, head().name());
        }
        if (existing.isEmpty() && Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        {
            throw Errors.badPath(aCheckedPath, "exists but is not a regular file");
        }
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".cumba-editor-tmp");
        Files.write(tmp, aContent);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        return blobId(aContent);
    }

    // ---- commits -------------------------------------------------------------------------


    /**
     * Stages every uncommitted change under the allow-list and commits it, hooks off.
     *
     * @throws EditorException
     *             409 {@code DEFAULT_BRANCH}, 409 {@code NOTHING_TO_COMMIT}
     */
    public CommitInfo commit(String aMessage, String aName, String aEmail) throws IOException
    {
        String branch = currentBranch();
        requireNotDefault(branch);
        WorkTreeStatus st = status();
        if (st.allowed().isEmpty())
        {
            throw Errors.nothingToCommit();
        }
        try
        {
            List<String> toAdd = new ArrayList<>();
            List<String> toRemove = new ArrayList<>();
            for (ChangedFile f : st.allowed())
            {
                (f.kind() == ChangedFile.Kind.DELETED ? toRemove : toAdd).add(f.path());
            }
            if (!toAdd.isEmpty())
            {
                var add = git.add();
                toAdd.forEach(add::addFilepattern);
                add.call();
            }
            if (!toRemove.isEmpty())
            {
                var rm = git.rm().setCached(true);
                toRemove.forEach(rm::addFilepattern);
                rm.call();
            }
            RevCommit c = git.commit().setMessage(aMessage).setAuthor(aName, aEmail)
                    .setCommitter(aName, aEmail).setNoVerify(true).setAllowEmpty(false).call();
            return new CommitInfo(c.getName(), c.getShortMessage(), aName);
        }
        catch (EmptyCommitException e)
        {
            EditorException nothing = Errors.nothingToCommit();
            nothing.initCause(e);
            throw nothing;
        }
        catch (GitAPIException e)
        {
            throw Errors.git("git commit failed", e);
        }
    }

    // ---- discard -------------------------------------------------------------------------


    /**
     * Discards the saved, uncommitted changes under the allow-list — exactly the set
     * {@link #commit} would stage, so discard is commit's inverse (§5.2, the dirty-tree dialog's
     * <i>Discard</i>). A path HEAD holds is restored from HEAD (index and working tree); a path
     * HEAD lacks (a new rule or scenario the editor wrote, staged or not) is unstaged and deleted,
     * and the directories that held only it are removed up to, never including, the allow-list
     * root.
     *
     * <p>
     * What is never touched: commits, refs and other branches (no reset of HEAD); the
     * {@link WorkTreeStatus#ignored() changes outside the allow-list} (never produced through the
     * API, so not the user's work — they keep the tree dirty, as they keep commit from cleaning
     * it); git-ignored files; anything outside the clone.
     * </p>
     *
     * <p>
     * ⚠ The file system is acted on ONLY for paths {@code git status} reported, never for a
     * client-supplied path: the client's list is compared as a set and nothing more. Every path is
     * resolved through {@link PathPolicy#resolve} (containment, no symlink element) BEFORE the
     * first change, so one refused path leaves the whole tree as it was.
     * </p>
     *
     * @param aConfirmedPaths
     *            the files the user confirmed losing; must equal the current set
     * @return the changes discarded (empty when there were none and none were confirmed)
     * @throws EditorException
     *             409 {@code STALE} with the current {@code paths} when the confirmed set is not
     *             the current one, 400 {@code BAD_PATH} on a symlink or an escape
     */
    public List<ChangedFile> discard(Collection<String> aConfirmedPaths) throws IOException
    {
        List<ChangedFile> changes = status().allowed();
        Set<String> current = new TreeSet<>();
        changes.forEach(f -> current.add(f.path()));
        if (!current.equals(new TreeSet<>(aConfirmedPaths)))
        {
            throw Errors.staleChanges(List.copyOf(current), currentBranch(), head().name());
        }
        if (changes.isEmpty())
        {
            return List.of();
        }
        List<String> restore = new ArrayList<>();
        Map<String, Path> delete = new LinkedHashMap<>();
        try (RevWalk walk = new RevWalk(repo))
        {
            RevTree tree = walk.parseCommit(head()).getTree();
            for (ChangedFile f : changes)
            {
                Path target = PathPolicy.resolve(dir, f.path());
                try (TreeWalk tw = TreeWalk.forPath(repo, f.path(), tree))
                {
                    if (tw == null)
                    {
                        delete.put(f.path(), target);
                    }
                    else
                    {
                        restore.add(f.path());
                    }
                }
            }
        }
        try
        {
            if (!restore.isEmpty())
            {
                var checkout = git.checkout().setStartPoint(Constants.HEAD);
                restore.forEach(checkout::addPath);
                checkout.call();
            }
            if (!delete.isEmpty())
            {
                var reset = git.reset().setRef(Constants.HEAD);
                delete.keySet().forEach(reset::addPath);
                reset.call();
            }
        }
        catch (GitAPIException e)
        {
            throw Errors.git("discarding the uncommitted changes failed", e);
        }
        Path root = dir.toRealPath();
        for (Map.Entry<String, Path> e : delete.entrySet())
        {
            Files.deleteIfExists(e.getValue());
            pruneEmptyParents(root, e.getKey());
        }
        return changes;
    }


    /**
     * Removes the directories a deleted file leaves empty, walking up while the directory lies
     * strictly inside an allow-list root (a root itself and everything above it stay).
     */
    private static void pruneEmptyParents(Path aRoot, String aPath) throws IOException
    {
        int slash = aPath.lastIndexOf('/');
        String rel = slash < 0 ? "" : aPath.substring(0, slash);
        while (!rel.isEmpty() && PathPolicy.isAllowed(rel) && !PathPolicy.ROOTS.contains(rel))
        {
            Path d = aRoot.resolve(rel);
            if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS) || !isEmptyDirectory(d))
            {
                return;
            }
            Files.delete(d);
            slash = rel.lastIndexOf('/');
            rel = slash < 0 ? "" : rel.substring(0, slash);
        }
    }


    private static boolean isEmptyDirectory(Path aDir) throws IOException
    {
        try (var entries = Files.list(aDir))
        {
            return entries.findAny().isEmpty();
        }
    }


    @Override
    public void close()
    {
        git.close();
    }
}
