package net.cumba.corej.editor.forge;

/**
 * The identity the forge reports for a token (§5.1.3): the immutable numeric id that keys the
 * workspace (§5.1.5, review M1), the login, and the commit identity (§5.1.7). A blank name falls
 * back to the login — Gitea's {@code full_name} is empty for an account that never set one
 * (measured 2026-10-06).
 *
 * @param id
 *            the forge's numeric user id
 * @param login
 *            the login name
 * @param name
 *            the display name, never blank
 * @param email
 *            the commit email, never blank
 */
public record ForgeUser(long id, String login, String name, String email)
{

    public ForgeUser
    {
        if (login.isBlank())
        {
            throw new IllegalArgumentException("the forge reported a blank login");
        }
        if (name.isBlank())
        {
            name = login;
        }
        if (email.isBlank())
        {
            throw new IllegalArgumentException("no commit email for " + login);
        }
    }
}
