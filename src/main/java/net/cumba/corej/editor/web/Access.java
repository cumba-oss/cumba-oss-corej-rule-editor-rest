package net.cumba.corej.editor.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.error.EditorException;
import net.cumba.corej.editor.error.Errors;
import net.cumba.corej.editor.git.AnonymousClone;
import net.cumba.corej.editor.git.ChangedFile;
import net.cumba.corej.editor.git.CorpusSource;
import net.cumba.corej.editor.git.Workspace;
import net.cumba.corej.editor.git.WorkspaceService;
import net.cumba.corej.editor.session.LoginService;
import net.cumba.corej.editor.session.SessionCredentials;
import net.cumba.corej.editor.session.SessionUser;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * How a request reaches a corpus (§5.8.1): the workspace comes ONLY from the principal — no user or
 * path parameter selects it — and every access runs under that workspace's lock (§5.8.4). A request
 * without a session reads the anonymous snapshot when the instance serves one (§5.1.6), else it is
 * 401.
 *
 * <p>
 * The lock is waited for at most {@code editor.limits.workspace-lock-wait}, then the request is
 * refused with {@code 503 WORKSPACE_BUSY} (security review M1): no request parks a server thread
 * for ever behind another request of the same user. Whatever may run long — a run's worker, the
 * corpus index build — happens OUTSIDE the lock, on what the locked part exported or snapshotted.
 * </p>
 */
@Component
public class Access
{

    /** The 503 code of a workspace whose lock did not come free in time. */
    public static final String WORKSPACE_BUSY = "WORKSPACE_BUSY";

    /**
     * What a read sees: a source, where it is, and the uncommitted changes (empty for a snapshot).
     * The changes are computed on first use only (REST review #4: a full {@code git status} walks
     * every tracked file, and most reads never look at it); a view is used inside the {@link Read}
     * that received it, under the lock — except {@link #headSnapshot()}, which stays valid after
     * it.
     */
    public static final class View
    {

        private final CorpusSource source;

        private final CorpusSource headSnapshot;

        private final String branch;

        private final String head;

        private final boolean anonymous;

        private final Supplier<Map<String, ChangedFile.Kind>> changeSupplier;

        private @Nullable Map<String, ChangedFile.Kind> changes;

        public View(CorpusSource aSource, CorpusSource aHeadSnapshot, String aBranch, String aHead,
                boolean aAnonymous, Supplier<Map<String, ChangedFile.Kind>> aChanges)
        {
            source = aSource;
            headSnapshot = aHeadSnapshot;
            branch = aBranch;
            head = aHead;
            anonymous = aAnonymous;
            changeSupplier = aChanges;
        }


        /** The source a read reads: the working tree, or the anonymous snapshot. */
        public CorpusSource source()
        {
            return source;
        }


        /** The HEAD commit, immutable: readable after the lock is released. */
        public CorpusSource headSnapshot()
        {
            return headSnapshot;
        }


        public String branch()
        {
            return branch;
        }


        public String head()
        {
            return head;
        }


        public boolean anonymous()
        {
            return anonymous;
        }


        /** The uncommitted changes under the allow-list, computed once on first use. */
        public Map<String, ChangedFile.Kind> changes()
        {
            Map<String, ChangedFile.Kind> c = changes;
            if (c == null)
            {
                c = Map.copyOf(changeSupplier.get());
                changes = c;
            }
            return c;
        }
    }


    /** A read against a view. */
    @FunctionalInterface
    public interface Read<T>
    {

        T apply(View aView) throws IOException;
    }


    /** An operation on a session's workspace. */
    @FunctionalInterface
    public interface Operation<T>
    {

        T apply(Workspace aWorkspace, SessionCredentials aCredentials) throws IOException;
    }

    private final WorkspaceService workspaces;

    private final AnonymousClone anonymous;

    private final Duration lockWait;

    @Autowired
    public Access(WorkspaceService aWorkspaces, AnonymousClone aAnonymous,
            EditorProperties aProperties)
    {
        this(aWorkspaces, aAnonymous, aProperties.getLimits().getWorkspaceLockWait());
    }


    Access(WorkspaceService aWorkspaces, AnonymousClone aAnonymous, Duration aLockWait)
    {
        workspaces = aWorkspaces;
        anonymous = aAnonymous;
        lockWait = aLockWait;
    }


    /**
     * The session's workspace, under its lock — waited for at most the configured time.
     *
     * @throws EditorException
     *             {@code 503 WORKSPACE_BUSY} when another request of the user holds the lock longer
     */
    public <T> T workspace(SessionUser aUser, HttpServletRequest aRequest, Operation<T> aOp)
        throws IOException
    {
        var credentials = LoginService.credentials(aRequest);
        // A workspace the retention sweep removed while this request waited for its lock is
        // retired: take the service's current one instead (one retry; a second is a real fault).
        for (int attempt = 0;; attempt++)
        {
            Workspace ws = workspaces.workspace(aUser.id(), aUser.defaultBranch(),
                    credentials.git());
            lock(ws);
            try
            {
                if (ws.isRetired())
                {
                    if (attempt == 0)
                    {
                        continue;
                    }
                    throw busy();
                }
                // identity and last use are written under the lock, never before it
                ws.markUsedBy(aUser.name(), aUser.email());
                return aOp.apply(ws, credentials);
            }
            finally
            {
                ws.lock().unlock();
            }
        }
    }


    private void lock(Workspace aWorkspace)
    {
        boolean locked;
        try
        {
            locked = aWorkspace.lock().tryLock(lockWait.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (!locked)
        {
            throw busy();
        }
    }


    private EditorException busy()
    {
        return new EditorException(HttpStatus.SERVICE_UNAVAILABLE, WORKSPACE_BUSY,
                "another request of this user holds the workspace (a push, a fetch, a branch "
                        + "switch …) — try again shortly");
    }


    /** A read: the session's working tree, else the anonymous snapshot, else 401. */
    public <T> T read(@Nullable SessionUser aUser, HttpServletRequest aRequest, Read<T> aRead)
        throws IOException
    {
        if (aUser != null)
        {
            return workspace(aUser, aRequest, (ws, c) ->
            {
                View view = new View(ws.source(), ws.headSnapshot(), ws.currentBranch(),
                        ws.head().name(), false, () ->
                        {
                            Map<String, ChangedFile.Kind> changes = new LinkedHashMap<>();
                            ws.status().allowed().forEach(f -> changes.put(f.path(), f.kind()));
                            return changes;
                        });
                return aRead.apply(view);
            });
        }
        AnonymousClone.Snapshot snapshot = anonymous.snapshot()
                .orElseThrow(Access::noAnonymousRead);
        return aRead.apply(new View(snapshot.source(), snapshot.source(), snapshot.branch(),
                snapshot.commit().name(), true, Map::of));
    }


    /**
     * The gate of an endpoint that needs no corpus (the scenario grid's text operations): a
     * session, or an instance that serves anonymous reads — else 401, as {@link #read} answers. The
     * security chain applies the same rule before the body is read (security review M2); this is
     * the endpoint's own statement of it.
     */
    public void requireReader(@Nullable SessionUser aUser)
    {
        if (aUser == null && anonymous.snapshot().isEmpty())
        {
            throw noAnonymousRead();
        }
    }


    private static EditorException noAnonymousRead()
    {
        return Errors
                .unauthenticated("log in first (this instance serves no anonymous read access)");
    }
}
