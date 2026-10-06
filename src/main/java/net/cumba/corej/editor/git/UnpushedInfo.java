package net.cumba.corej.editor.git;

import java.util.List;

/**
 * The push status of a local branch, precisely (§5.2, review M5): unpushed when HEAD is not
 * reachable from the remote-tracking ref, OR when there is no tracking ref and HEAD is not the last
 * SHA this server pushed for the branch. A branch the forge squash-merged and deleted therefore
 * stops counting as unpushed once its tracking ref is pruned.
 *
 * @param unpushed
 *            the branch holds work the remote lacks (or has never been pushed)
 * @param hasTrackingRef
 *            {@code refs/remotes/origin/<branch>} exists and the branch tracks it
 * @param aheadCount
 *            commits the remote (or the last pushed SHA, or the default branch) lacks
 * @param behindCount
 *            commits the tracking ref has that the branch lacks; 0 without a tracking ref
 * @param commits
 *            the unpushed commits, newest first
 */
public record UnpushedInfo(boolean unpushed, boolean hasTrackingRef, int aheadCount,
        int behindCount, List<CommitInfo> commits)
{

    public UnpushedInfo
    {
        commits = List.copyOf(commits);
    }
}
