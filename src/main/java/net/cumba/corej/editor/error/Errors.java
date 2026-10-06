package net.cumba.corej.editor.error;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The editor's error vocabulary: one factory per {@code code}, so a controller test can assert the
 * code and the SPA can switch on it. Every mutating rule of PLAN-rule-editor §5.8 answers through
 * one of these.
 */
public final class Errors
{

    private Errors()
    {
    }


    /** 401 — no session, and anonymous read is not available for this instance. */
    public static EditorException unauthenticated(String aMessage)
    {
        return new EditorException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", aMessage);
    }


    /** 401 — the forge refused the token, or it cannot read the configured repository. */
    public static EditorException forgeRefused(String aMessage)
    {
        return new EditorException(HttpStatus.UNAUTHORIZED, "FORGE_REFUSED", aMessage);
    }


    /** 502 — the forge API or the git remote did not answer usably. */
    public static EditorException forgeUnavailable(String aMessage)
    {
        return new EditorException(HttpStatus.BAD_GATEWAY, "FORGE_UNAVAILABLE", aMessage);
    }


    /** 403 — a read-only session called a mutating endpoint (§5.8.1). */
    public static EditorException readOnly()
    {
        return new EditorException(HttpStatus.FORBIDDEN, "READ_ONLY",
                "this session's token can read the repository but not write it: "
                        + "save, commit and push are refused");
    }


    /** 400 — a repository path outside the allow-list, or malformed (§5.8.2). */
    public static EditorException badPath(String aPath, String aWhy)
    {
        return new EditorException(HttpStatus.BAD_REQUEST, "BAD_PATH",
                "path '" + aPath + "' refused: " + aWhy, Map.of("path", aPath));
    }


    /** 400 — a malformed request that is not a path problem. */
    public static EditorException badRequest(String aMessage)
    {
        return new EditorException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", aMessage);
    }


    /** 404 — the file, branch or rule does not exist. */
    public static EditorException notFound(String aWhat)
    {
        return new EditorException(HttpStatus.NOT_FOUND, "NOT_FOUND", aWhat + " not found");
    }


    /**
     * 409 — the request's {@code {branch, head, blob}} no longer describes the workspace (§5.8.4).
     * The details carry the current branch and HEAD so the UI can reload.
     */
    public static EditorException stale(String aWhat, String aBranch, String aHead)
    {
        return new EditorException(HttpStatus.CONFLICT, "STALE",
                "stale request: " + aWhat + " changed since the client read it",
                Map.of("branch", aBranch, "head", aHead));
    }


    /**
     * 409 — a discard named other files than the workspace's uncommitted ones (another tab saved or
     * committed since the user confirmed). The details carry the current {@code paths}, branch and
     * HEAD, so the UI can ask again.
     */
    public static EditorException staleChanges(List<String> aPaths, String aBranch, String aHead)
    {
        return new EditorException(HttpStatus.CONFLICT, "STALE",
                "stale request: the uncommitted files changed since the client listed them",
                Map.of("paths", List.copyOf(aPaths), "branch", aBranch, "head", aHead));
    }


    /** 409 — a branch switch or update needs a clean working tree (§5.2). */
    public static EditorException dirtyTree(List<String> aPaths)
    {
        return new EditorException(HttpStatus.CONFLICT, "DIRTY_TREE",
                "the working tree has uncommitted changes: commit or discard them first",
                Map.of("paths", List.copyOf(aPaths)));
    }


    /** 409 — writes, commits and pushes on the default branch are refused (§5.2, review M2). */
    public static EditorException defaultBranch(String aBranch)
    {
        return new EditorException(HttpStatus.CONFLICT, "DEFAULT_BRANCH",
                "'" + aBranch + "' is the default branch: changes reach it only through a "
                        + "pull request, so create a branch first",
                Map.of("branch", aBranch));
    }


    /** 409 — the remote branch moved on; the push is reported, never forced (§5.2). */
    public static EditorException nonFastForward(String aBranch)
    {
        return new EditorException(HttpStatus.CONFLICT, "NON_FAST_FORWARD",
                "the remote '" + aBranch + "' has commits this workspace lacks: "
                        + "update from the remote branch, then push again",
                Map.of("branch", aBranch));
    }


    /** 409 — the merge conflicted and was aborted by resetting to the pre-merge HEAD (§5.2). */
    public static EditorException mergeConflict(String aRef, List<String> aPaths, String aHead)
    {
        return new EditorException(HttpStatus.CONFLICT, "MERGE_CONFLICT",
                "merging '" + aRef + "' conflicts; the workspace was reset to its pre-merge "
                        + "HEAD (conflict resolution is not supported in this version)",
                Map.of("ref", aRef, "paths", List.copyOf(aPaths), "head", aHead));
    }


    /** 409 — nothing is staged for a commit. */
    public static EditorException nothingToCommit()
    {
        return new EditorException(HttpStatus.CONFLICT, "NOTHING_TO_COMMIT",
                "no saved changes to commit");
    }


    /** 409 — the branch already exists locally or on the remote. */
    public static EditorException branchExists(String aBranch)
    {
        return new EditorException(HttpStatus.CONFLICT, "BRANCH_EXISTS",
                "branch '" + aBranch + "' already exists", Map.of("branch", aBranch));
    }


    /** 409 — deleting a branch that still holds unpushed work. */
    public static EditorException unpushed(String aBranch)
    {
        return new EditorException(HttpStatus.CONFLICT, "UNPUSHED",
                "branch '" + aBranch + "' has commits the remote lacks; push them first",
                Map.of("branch", aBranch));
    }


    /** 409 — the current branch cannot be deleted. */
    public static EditorException currentBranch(String aBranch)
    {
        return new EditorException(HttpStatus.CONFLICT, "CURRENT_BRANCH",
                "'" + aBranch + "' is checked out; switch to another branch first",
                Map.of("branch", aBranch));
    }


    /** 500 — a git operation failed for a reason that is not one of the contract's states. */
    public static EditorException git(String aMessage, Throwable aCause)
    {
        EditorException e = new EditorException(HttpStatus.INTERNAL_SERVER_ERROR, "GIT_FAILED",
                aMessage + ": " + aCause.getMessage());
        e.initCause(aCause);
        return e;
    }
}
