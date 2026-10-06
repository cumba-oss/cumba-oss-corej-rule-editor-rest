package net.cumba.corej.editor.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.cumba.corej.editor.config.EditorProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The retention sweep (§5.1.5): a workspace untouched for {@code editor.workspace.retention-days}
 * and holding nothing uncommitted or unpushed is deleted; disk use is reported in the log. A
 * workspace with work in it is never deleted, however old. 0 days disables the sweep.
 */
@Component
public class WorkspaceRetention
{

    private static final System.Logger LOG = System.getLogger(WorkspaceRetention.class.getName());

    private final WorkspaceService workspaces;

    private final Duration retention;

    @Autowired
    public WorkspaceRetention(WorkspaceService aWorkspaces, EditorProperties aProperties)
    {
        this(aWorkspaces, Duration.ofDays(aProperties.getWorkspace().getRetentionDays()));
    }


    WorkspaceRetention(WorkspaceService aWorkspaces, Duration aRetention)
    {
        workspaces = aWorkspaces;
        retention = aRetention;
    }

    /**
     * One sweep's outcome.
     *
     * @param deleted
     *            user ids whose workspace was removed
     * @param kept
     *            user ids whose workspace stays (in use, or holding work)
     * @param bytesInUse
     *            disk use of the kept workspaces
     */
    public record Report(List<Long> deleted, List<Long> kept, long bytesInUse)
    {

        public Report
        {
            deleted = List.copyOf(deleted);
            kept = List.copyOf(kept);
        }
    }

    @Scheduled(fixedDelayString = "${editor.workspace.retention-interval:PT6H}",
            initialDelayString = "${editor.workspace.retention-interval:PT6H}")
    public void scheduledSweep()
    {
        try
        {
            Report r = sweep(Instant.now());
            LOG.log(System.Logger.Level.INFO,
                    "workspace retention: {0} deleted, {1} kept, {2} MiB in use",
                    r.deleted().size(), r.kept().size(), r.bytesInUse() / (1024 * 1024));
        }
        catch (IOException e)
        {
            LOG.log(System.Logger.Level.WARNING, "workspace retention sweep failed", e);
        }
    }


    /** Runs one sweep as of {@code aNow}. */
    public Report sweep(Instant aNow) throws IOException
    {
        List<Long> deleted = new ArrayList<>();
        List<Long> kept = new ArrayList<>();
        long bytes = 0;
        for (long id : workspaces.userIdsOnDisk())
        {
            if (!retention.isZero() && expired(id, aNow))
            {
                workspaces.delete(id);
                deleted.add(id);
            }
            else
            {
                kept.add(id);
                bytes += size(workspaces.directoryFor(id));
            }
        }
        return new Report(deleted, kept, bytes);
    }


    @SuppressWarnings("PMD.CloseResource") // owned by the WorkspaceService
    private boolean expired(long aUserId, Instant aNow) throws IOException
    {
        Workspace ws = workspaces.openQuietly(aUserId);
        ws.lock().lock();
        try
        {
            if (ws.lastUsed().plus(retention).isAfter(aNow))
            {
                return false;
            }
            if (!ws.status().isClean())
            {
                return false;
            }
            for (BranchInfo b : ws.branches())
            {
                UnpushedInfo u = b.unpushed();
                if (u != null && u.unpushed() && !b.isDefault())
                {
                    return false;
                }
            }
            return true;
        }
        finally
        {
            ws.lock().unlock();
        }
    }


    static long size(Path aDir) throws IOException
    {
        if (!Files.isDirectory(aDir))
        {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(aDir))
        {
            long total = 0;
            for (Path p : walk.toList())
            {
                if (Files.isRegularFile(p))
                {
                    total += Files.size(p);
                }
            }
            return total;
        }
    }
}
