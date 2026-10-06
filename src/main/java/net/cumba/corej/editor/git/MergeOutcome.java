package net.cumba.corej.editor.git;

/**
 * A successful update-from-default or update-from-remote-branch.
 *
 * @param ref
 *            what was merged, e.g. {@code origin/main}
 * @param status
 *            {@code ALREADY_UP_TO_DATE}, {@code FAST_FORWARD} or {@code MERGED}
 * @param head
 *            the branch's commit afterwards
 */
public record MergeOutcome(String ref, String status, String head)
{
}
