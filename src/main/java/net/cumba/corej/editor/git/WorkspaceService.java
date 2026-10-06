package net.cumba.corej.editor.git;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.error.EditorException;
import net.cumba.corej.editor.error.Errors;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The workspaces under {@code editor.workspace-root}: one directory per forge user id (§5.1.5,
 * review M1 — never the login), opened on first use and kept open; cloned on first login with the
 * user's own token (§5.1.6). The empty, server-owned {@code .hooks-disabled/} directory every
 * clone's {@code core.hooksPath} points at lives here too (§5.8.3).
 */
@Service
public final class WorkspaceService
{

    static final String HOOKS_DIR = ".hooks-disabled";

    private final Path root;

    private final Path hooksDir;

    private final String repositoryUrl;

    private final Map<Long, Workspace> open = new ConcurrentHashMap<>();

    private final Map<Long, Object> creation = new ConcurrentHashMap<>();

    @Autowired
    public WorkspaceService(EditorProperties aProperties) throws IOException
    {
        this(aProperties.workspaceRootPath(), aProperties.coordinates().url());
    }


    WorkspaceService(Path aRoot, String aRepositoryUrl) throws IOException
    {
        root = aRoot;
        repositoryUrl = aRepositoryUrl;
        hooksDir = aRoot.resolve(HOOKS_DIR);
        Files.createDirectories(hooksDir);
        try (Stream<Path> stale = Files.list(hooksDir))
        {
            if (stale.findAny().isPresent())
            {
                throw new IOException(hooksDir + " must stay EMPTY: it is every workspace's "
                        + "core.hooksPath, so anything in it would run as the server");
            }
        }
    }


    public Path root()
    {
        return root;
    }


    Path hooksDir()
    {
        return hooksDir;
    }


    /** The workspace directory for a forge user id. */
    public Path directoryFor(long aUserId)
    {
        return root.resolve(Long.toString(aUserId));
    }


    /**
     * The user's workspace, cloned with the session's credentials when it does not exist yet, and
     * marked used.
     *
     * @throws EditorException
     *             502 when the clone cannot be created
     */
    @SuppressWarnings("PMD.CloseResource") // kept open in the map, closed by closeAll / delete
    public Workspace workspace(long aUserId, String aDefaultBranch, String aName, String aEmail,
            @Nullable CredentialsProvider aCredentials)
    {
        Workspace ws = open.get(aUserId);
        if (ws == null)
        {
            // one clone at a time per user, without blocking the other users' map access
            synchronized (creation.computeIfAbsent(aUserId, id -> new Object()))
            {
                ws = open.computeIfAbsent(aUserId,
                        id -> openOrCreate(id, aDefaultBranch, aCredentials));
            }
        }
        try
        {
            ws.setIdentity(aName, aEmail);
            ws.touch();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        return ws;
    }


    private Workspace openOrCreate(long aUserId, String aDefaultBranch,
            @Nullable CredentialsProvider aCredentials)
    {
        Path dir = directoryFor(aUserId);
        try
        {
            if (Workspace.exists(dir))
            {
                return Workspace.open(dir, hooksDir);
            }
            return Workspace.create(dir, repositoryUrl, aDefaultBranch, aCredentials, hooksDir);
        }
        catch (IOException e)
        {
            EditorException ex = Errors
                    .forgeUnavailable("preparing the workspace failed: " + e.getMessage());
            ex.initCause(e);
            throw ex;
        }
    }


    /** The open workspace of a user, if any (no clone, no touch). */
    public @Nullable Workspace peek(long aUserId)
    {
        return open.get(aUserId);
    }


    /** Every workspace directory on disk, by user id. */
    public List<Long> userIdsOnDisk() throws IOException
    {
        List<Long> ids = new ArrayList<>();
        if (!Files.isDirectory(root))
        {
            return ids;
        }
        try (Stream<Path> dirs = Files.list(root))
        {
            for (Path d : dirs.toList())
            {
                String name = d.getFileName().toString();
                if (Files.isDirectory(d) && name.chars().allMatch(Character::isDigit))
                {
                    ids.add(Long.parseLong(name));
                }
            }
        }
        return ids;
    }


    /** Opens a workspace from disk without marking it used (the retention sweep's read). */
    public Workspace openQuietly(long aUserId) throws IOException
    {
        Workspace ws = open.get(aUserId);
        if (ws != null)
        {
            return ws;
        }
        synchronized (creation.computeIfAbsent(aUserId, id -> new Object()))
        {
            try
            {
                return open.computeIfAbsent(aUserId, id ->
                {
                    try
                    {
                        return Workspace.open(directoryFor(id), hooksDir);
                    }
                    catch (IOException e)
                    {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            catch (UncheckedIOException e)
            {
                throw new IOException(
                        "opening the workspace of user " + aUserId + " failed: " + e.getMessage(),
                        e);
            }
        }
    }


    /** Closes and deletes a workspace (retention). */
    @SuppressWarnings("PMD.CloseResource") // this IS the close
    public void delete(long aUserId) throws IOException
    {
        synchronized (creation.computeIfAbsent(aUserId, id -> new Object()))
        {
            Workspace ws = open.remove(aUserId);
            if (ws != null)
            {
                ws.close();
            }
            deleteTree(directoryFor(aUserId));
        }
    }


    static void deleteTree(Path aDir) throws IOException
    {
        if (!Files.exists(aDir))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(aDir))
        {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList())
            {
                Files.delete(p);
            }
        }
    }


    @PreDestroy
    public void closeAll()
    {
        open.values().forEach(Workspace::close);
        open.clear();
    }
}
