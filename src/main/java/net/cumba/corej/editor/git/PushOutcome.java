package net.cumba.corej.editor.git;

/**
 * A successful push.
 *
 * @param branch
 *            the pushed branch
 * @param head
 *            the commit now on the remote
 * @param status
 *            {@code OK} or {@code UP_TO_DATE}
 * @param created
 *            the remote branch did not exist before
 */
public record PushOutcome(String branch, String head, String status, boolean created)
{
}
