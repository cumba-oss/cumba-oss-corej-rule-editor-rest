package net.cumba.corej.editor.git;

/**
 * One uncommitted change in a working tree, as {@code git status} reports it.
 *
 * @param path
 *            repository-relative
 * @param kind
 *            what happened to it
 */
public record ChangedFile(String path, Kind kind)
{

    /** The change kinds the editor distinguishes. */
    public enum Kind
    {
        /** Not yet tracked by git (a new rule or scenario). */
        UNTRACKED,
        /** Staged as new. */
        ADDED,
        /** Tracked and changed (staged or not). */
        MODIFIED,
        /** Tracked and gone from the tree, or staged for removal. */
        DELETED,
        /** Left in a conflicted state — never produced by the editor, reported if found. */
        CONFLICTING
    }
}
