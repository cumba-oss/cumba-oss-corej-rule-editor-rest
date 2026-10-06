package net.cumba.corej.editor.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.springframework.stereotype.Component;

/**
 * How a request reaches a corpus (§5.8.1): the workspace comes ONLY from the principal — no user or
 * path parameter selects it — and every access runs under that workspace's lock (§5.8.4). A request
 * without a session reads the anonymous snapshot when the instance serves one (§5.1.6), else it is
 * 401.
 */
@Component
public class Access
{

    /**
     * What a read sees: a source, where it is, and the uncommitted changes (empty for a snapshot).
     */
    public record View(CorpusSource source, String branch, String head,
            Map<String, ChangedFile.Kind> changes, boolean anonymous)
    {

        public View
        {
            changes = Map.copyOf(changes);
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

    public Access(WorkspaceService aWorkspaces, AnonymousClone aAnonymous)
    {
        workspaces = aWorkspaces;
        anonymous = aAnonymous;
    }


    /** The session's workspace, under its lock. */
    public <T> T workspace(SessionUser aUser, HttpServletRequest aRequest, Operation<T> aOp)
        throws IOException
    {
        var credentials = LoginService.credentials(aRequest);
        Workspace ws = workspaces.workspace(aUser.id(), aUser.defaultBranch(), aUser.name(),
                aUser.email(), credentials.git());
        ws.lock().lock();
        try
        {
            return aOp.apply(ws, credentials);
        }
        finally
        {
            ws.lock().unlock();
        }
    }


    /** A read: the session's working tree, else the anonymous snapshot, else 401. */
    public <T> T read(@Nullable SessionUser aUser, HttpServletRequest aRequest, Read<T> aRead)
        throws IOException
    {
        if (aUser != null)
        {
            return workspace(aUser, aRequest, (ws, c) ->
            {
                Map<String, ChangedFile.Kind> changes = new LinkedHashMap<>();
                ws.status().allowed().forEach(f -> changes.put(f.path(), f.kind()));
                return aRead.apply(new View(ws.source(), ws.currentBranch(), ws.head().name(),
                        changes, false));
            });
        }
        AnonymousClone.Snapshot snapshot = anonymous.snapshot()
                .orElseThrow(() -> Errors.unauthenticated(
                        "log in first (this instance serves no anonymous" + " read access)"));
        return aRead.apply(new View(snapshot.source(), snapshot.branch(), snapshot.commit().name(),
                Map.of(), true));
    }
}
