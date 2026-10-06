package net.cumba.corej.editor.git;

import org.jspecify.annotations.Nullable;

/**
 * One branch in the picker (§5.2).
 *
 * @param name
 *            the short name
 * @param kind
 *            local or remote-only
 * @param head
 *            the branch's commit
 * @param current
 *            checked out in the workspace
 * @param isDefault
 *            the repository's default branch, never selectable for writing
 * @param unpushed
 *            the push status; null for a remote-only branch
 */
public record BranchInfo(String name, Kind kind, String head, boolean current, boolean isDefault,
        @Nullable UnpushedInfo unpushed)
{

    /** Where the branch exists. */
    public enum Kind
    {
        /** A local branch of the workspace (it may also exist on the remote). */
        LOCAL,
        /** A remote branch the workspace has not checked out. */
        REMOTE
    }
}
