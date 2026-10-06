package net.cumba.corej.editor.forge;

import net.cumba.corej.editor.config.RepositoryCoordinates;

/**
 * The forge behind the bound repository, as the login probe needs it (§5.1.3): who the token
 * belongs to, what it may do with the repository, and — decided by the TOKEN, not the role (review
 * M4) — whether it can push. Implemented over {@code java.net.http} for Gitea and GitHub; tests use
 * a fake.
 *
 * <p>
 * Failures are reported as {@code EditorException}s: {@code FORGE_REFUSED} (401) when the token is
 * bad or cannot read the repository — Gitea answers no access with 404, never 401 (measured
 * 2026-10-06) — and {@code FORGE_UNAVAILABLE} (502) when the forge did not answer usably.
 * </p>
 */
public interface ForgeClient
{

    /** {@code GET /user}: the token's owner. */
    ForgeUser user(ForgeCredentials aCredentials);


    /** {@code GET /repos/{owner}/{repo}}: the user's permissions and the default branch. */
    RepositoryAccess repository(ForgeCredentials aCredentials, RepositoryCoordinates aRepository);


    /**
     * The receive-pack advertisement probe:
     * {@code GET <clone-url>/info/refs?service=git-receive-pack} with the token as basic auth. 200
     * means the token can push (edit mode); Gitea answers 403 for a read token (measured
     * 2026-10-06). No ref is updated.
     */
    boolean canReceivePack(ForgeCredentials aCredentials, RepositoryCoordinates aRepository);


    /**
     * The anonymous-visibility probe for the optional anonymous clone (§5.1.6):
     * {@code GET <clone-url>/info/refs?service=git-upload-pack} with no credentials; 200 means the
     * repository is public.
     */
    boolean anonymouslyReadable(RepositoryCoordinates aRepository);


    /** The forge's "create pull request" page for a pushed branch (§5.2). */
    String pullRequestUrl(RepositoryCoordinates aRepository, String aDefaultBranch, String aBranch);
}
