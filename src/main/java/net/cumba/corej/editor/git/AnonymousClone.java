package net.cumba.corej.editor.git;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.config.RepositoryCoordinates;
import net.cumba.corej.editor.forge.ForgeClient;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The optional anonymous read mode (§5.1.6, RE-Q12): a server-owned BARE clone of the default
 * branch under {@code <workspace-root>/.anonymous.git}, fetched without credentials. Reads are
 * served from a commit {@link SnapshotSource snapshot} (§5.8.4), never from a working tree. Every
 * refresh first re-probes anonymous visibility, and the mode switches itself off when the probe
 * fails: a repository turned private must stop being served.
 */
@Component
public class AnonymousClone
{

    static final String DIR = ".anonymous.git";

    private static final System.Logger LOG = System.getLogger(AnonymousClone.class.getName());

    private final boolean configured;

    private final RepositoryCoordinates repository;

    private final Optional<String> configuredDefaultBranch;

    private final Path dir;

    private final ForgeClient forge;

    private final int gitTimeoutSeconds;

    private volatile boolean enabled;

    private volatile @Nullable Git git;

    private volatile @Nullable Snapshot snapshot;

    /** The served state: a branch at a commit, read through a snapshot. */
    public record Snapshot(String branch, ObjectId commit, SnapshotSource source)
    {
    }

    public AnonymousClone(EditorProperties aProperties, ForgeClient aForge)
    {
        configured = aProperties.getAnonymous().isEnabled();
        repository = aProperties.coordinates();
        configuredDefaultBranch = aProperties.getRepository().configuredDefaultBranch();
        dir = aProperties.workspaceRootPath().resolve(DIR);
        forge = aForge;
        gitTimeoutSeconds = WorkspaceService.timeoutSeconds(aProperties);
    }


    @PostConstruct
    void start()
    {
        enabled = configured;
        if (enabled)
        {
            refresh();
        }
    }


    /** Whether anonymous reads are served right now. */
    public boolean available()
    {
        return enabled && snapshot != null;
    }


    /** The current snapshot, when available. */
    public Optional<Snapshot> snapshot()
    {
        return enabled ? Optional.ofNullable(snapshot) : Optional.empty();
    }


    @Scheduled(fixedDelayString = "${editor.anonymous.refresh-interval:PT10M}",
            initialDelayString = "${editor.anonymous.refresh-interval:PT10M}")
    public void scheduledRefresh()
    {
        if (enabled)
        {
            refresh();
        }
    }


    /**
     * Re-probes visibility, then fetches the default branch and moves the snapshot. Never throws: a
     * failure logs, keeps the previous snapshot, and — for a failed visibility probe — disables the
     * mode.
     */
    @SuppressWarnings("PMD.CloseResource") // the Git handle is owned by this bean, closed in stop()
    public synchronized void refresh()
    {
        if (!forge.anonymouslyReadable(repository))
        {
            if (enabled)
            {
                LOG.log(System.Logger.Level.WARNING,
                        "anonymous read disabled: {0} is not "
                                + "anonymously readable (the repository may have turned private)",
                        repository.url());
            }
            enabled = false;
            snapshot = null;
            return;
        }
        try
        {
            Git g = openOrInit();
            String branch = resolveDefaultBranch(g);
            g.fetch().setRemote(Workspace.ORIGIN)
                    .setRefSpecs(new RefSpec(
                            "+" + Constants.R_HEADS + branch + ":" + Constants.R_HEADS + branch))
                    .setTimeout(gitTimeoutSeconds).call();
            ObjectId commit = g.getRepository().resolve(Constants.R_HEADS + branch);
            if (commit == null)
            {
                throw new IOException("the remote has no branch '" + branch + "'");
            }
            snapshot = new Snapshot(branch, commit, new SnapshotSource(g.getRepository(), commit));
            enabled = true;
        }
        catch (IOException | GitAPIException | RuntimeException e)
        {
            LOG.log(System.Logger.Level.WARNING, "anonymous clone refresh failed: " + e, e);
        }
    }


    @SuppressWarnings("PMD.CloseResource") // stored in the field, closed in stop()
    private Git openOrInit() throws IOException, GitAPIException
    {
        Git g = git;
        if (g != null)
        {
            return g;
        }
        if (Files.isDirectory(dir.resolve("objects")))
        {
            g = Git.open(dir.toFile());
        }
        else
        {
            Files.createDirectories(dir);
            g = Git.init().setBare(true).setDirectory(dir.toFile()).call();
            StoredConfig c = g.getRepository().getConfig();
            c.setString("remote", Workspace.ORIGIN, "url", repository.url());
            c.save();
        }
        git = g;
        return g;
    }


    /** The configured default branch, else the remote's HEAD symref. */
    private String resolveDefaultBranch(Git aGit) throws GitAPIException
    {
        if (configuredDefaultBranch.isPresent())
        {
            return configuredDefaultBranch.get();
        }
        Map<String, Ref> refs = aGit.lsRemote().setRemote(Workspace.ORIGIN)
                .setTimeout(gitTimeoutSeconds).callAsMap();
        Ref head = refs.get(Constants.HEAD);
        if (head != null && head.isSymbolic()
                && head.getTarget().getName().startsWith(Constants.R_HEADS))
        {
            return head.getTarget().getName().substring(Constants.R_HEADS.length());
        }
        throw new IllegalStateException("the remote does not advertise a default branch; "
                + "set editor.repository.default-branch");
    }


    @PreDestroy
    @SuppressWarnings("PMD.CloseResource") // this IS the close
    void stop()
    {
        Git g = git;
        if (g != null)
        {
            g.close();
            git = null;
        }
    }
}
