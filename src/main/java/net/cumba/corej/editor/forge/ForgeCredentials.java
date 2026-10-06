package net.cumba.corej.editor.forge;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A login name and a personal access token (RE-Q5). {@link #toString()} never prints the token:
 * this record passes through log statements and exception messages.
 *
 * @param login
 *            the forge login, also the user name of git-over-HTTPS basic auth
 * @param token
 *            the personal access token
 */
public record ForgeCredentials(String login, String token)
{

    public ForgeCredentials
    {
        if (login.isBlank() || token.isBlank())
        {
            throw new IllegalArgumentException("login and token are required");
        }
    }


    /** The {@code Authorization: Basic} value git-over-HTTPS and the receive-pack probe send. */
    public String basicAuthorization()
    {
        return "Basic " + Base64.getEncoder()
                .encodeToString((login + ':' + token).getBytes(StandardCharsets.UTF_8));
    }


    @Override
    public String toString()
    {
        return "ForgeCredentials[login=" + login + ", token=***]";
    }
}
