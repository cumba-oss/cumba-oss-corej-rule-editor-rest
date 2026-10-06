package net.cumba.corej.editor.git;

/**
 * A commit, as the status and push endpoints name it.
 *
 * @param sha
 *            the full object id
 * @param message
 *            the short (first-line) message
 * @param author
 *            the author's name
 */
public record CommitInfo(String sha, String message, String author)
{
}
