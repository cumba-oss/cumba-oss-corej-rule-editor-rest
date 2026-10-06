package net.cumba.corej.editor.forge;

/**
 * What the forge says about the token's access to the bound repository ({@code permissions} of
 * {@code GET /repos/{owner}/{repo}}) plus the repository's default branch.
 *
 * <p>
 * ⚠ {@code push} describes the USER's role, not the token (review M4): a read-scoped token of a
 * user who may push answers {@code push: true} here. Edit mode therefore also needs
 * {@link ForgeClient#canReceivePack}.
 * </p>
 *
 * @param pull
 *            the user may read the repository
 * @param push
 *            the user may push to it
 * @param defaultBranch
 *            the repository's default branch
 * @param privateRepository
 *            whether the repository is private
 */
public record RepositoryAccess(boolean pull, boolean push, String defaultBranch,
        boolean privateRepository)
{
}
