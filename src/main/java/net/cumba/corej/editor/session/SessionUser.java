package net.cumba.corej.editor.session;

import net.cumba.corej.editor.error.Errors;

/**
 * The authenticated principal: the forge identity (§5.1.3), the mode the probe decided, and the
 * repository's default branch as the forge reported it at login. Deliberately NOT
 * {@code Serializable}: sessions are never persisted (§5.1.4), and an attempt to would fail loudly
 * rather than write a principal to disk.
 *
 * @param id
 *            the forge's numeric user id — the workspace key (§5.1.5)
 * @param login
 *            the login name
 * @param name
 *            the commit author name
 * @param email
 *            the commit author email
 * @param mode
 *            edit or read-only
 * @param defaultBranch
 *            the repository's default branch
 */
public record SessionUser(long id, String login, String name, String email, AccessMode mode,
        String defaultBranch)
{

    public boolean canWrite()
    {
        return mode == AccessMode.EDIT;
    }


    /** The §5.8.1 gate every mutating endpoint calls first. */
    public void requireEditMode()
    {
        if (!canWrite())
        {
            throw Errors.readOnly();
        }
    }
}
