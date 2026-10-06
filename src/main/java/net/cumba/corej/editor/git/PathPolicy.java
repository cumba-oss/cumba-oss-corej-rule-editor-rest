package net.cumba.corej.editor.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.cumba.corej.editor.error.EditorException;
import net.cumba.corej.editor.error.Errors;

/**
 * Path containment (PLAN-rule-editor §5.8.2, review H2). The file API accepts only
 * repository-relative paths on the allow-list — the check and docs trees and the scenario tree with
 * its sidecars — and refuses everything else BEFORE touching the file system: absolute paths,
 * backslashes, {@code .} / {@code ..} segments, any {@code .git} segment, and every dot-segment
 * (nothing in the allowed trees is hidden, and {@code .gitattributes} planted there would be a
 * second way to change git's behaviour). The resolved path must then stay inside the clone with
 * symlinks NOT followed, and no element of it may be a symlink — clones are created with
 * {@code core.symlinks=false}, so a committed link is checked out as a plain file, but a link
 * planted on disk is refused here as well.
 */
public final class PathPolicy
{

    /** The allowed trees, repository-relative, without a trailing slash. */
    public static final List<String> ROOTS = List.of("rules-src/checks", "rules-src/docs",
            "src/test/resources/net/cumba/corej/core/ruletestsuites");

    private PathPolicy()
    {
    }


    /**
     * Validates a repository-relative path and returns it normalised (forward slashes, no trailing
     * slash). A root itself is accepted (for directory listings); a path must otherwise lie
     * strictly inside a root.
     *
     * @throws EditorException
     *             400 {@code BAD_PATH}
     */
    public static String check(String aPath)
    {
        if (aPath.isBlank())
        {
            throw Errors.badPath(aPath, "empty");
        }
        if (aPath.indexOf('\\') >= 0 || aPath.indexOf('\0') >= 0)
        {
            throw Errors.badPath(aPath, "backslash or NUL");
        }
        if (aPath.startsWith("/"))
        {
            throw Errors.badPath(aPath, "absolute");
        }
        String path = aPath.endsWith("/") ? aPath.substring(0, aPath.length() - 1) : aPath;
        for (String segment : path.split("/", -1))
        {
            if (segment.isEmpty())
            {
                throw Errors.badPath(aPath, "empty segment");
            }
            if (".".equals(segment) || "..".equals(segment))
            {
                throw Errors.badPath(aPath, "'" + segment + "' segment");
            }
            if (".git".equals(segment.toLowerCase(Locale.ROOT)))
            {
                throw Errors.badPath(aPath, "a .git segment");
            }
            if (segment.charAt(0) == '.')
            {
                throw Errors.badPath(aPath, "a hidden segment '" + segment + "'");
            }
        }
        if (!isAllowed(path))
        {
            throw Errors.badPath(aPath, "outside the editable trees " + ROOTS);
        }
        return path;
    }


    /** Whether a normalised path is a root or lies inside one. Does not validate segments. */
    public static boolean isAllowed(String aPath)
    {
        for (String root : ROOTS)
        {
            if (aPath.equals(root) || aPath.startsWith(root + "/"))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Resolves a {@link #check checked} path inside the clone and proves containment on the real
     * file system: the clone root is canonicalised, every existing element of the path must be a
     * non-link, and the existing prefix's real path (links not followed) must stay under the root.
     * The returned path may not exist yet (a new file).
     *
     * @throws EditorException
     *             400 {@code BAD_PATH} on an escape or a symlink
     */
    public static Path resolve(Path aRoot, String aCheckedPath) throws IOException
    {
        Path root = aRoot.toRealPath();
        Path target = root.resolve(aCheckedPath).normalize();
        if (!target.startsWith(root))
        {
            throw Errors.badPath(aCheckedPath, "escapes the repository");
        }
        Path cursor = root;
        for (Path element : root.relativize(target))
        {
            cursor = cursor.resolve(element);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS))
            {
                break;
            }
            if (Files.isSymbolicLink(cursor))
            {
                throw Errors.badPath(aCheckedPath,
                        "'" + root.relativize(cursor) + "' is a symbolic link");
            }
            if (!cursor.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(root))
            {
                throw Errors.badPath(aCheckedPath, "escapes the repository");
            }
        }
        return target;
    }
}
