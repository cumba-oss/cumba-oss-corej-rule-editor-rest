package net.cumba.corej.editor.session;

import net.cumba.corej.editor.forge.ForgeCredentials;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/**
 * The token, held only in the server-side session (§5.1.4): handed to JGit through an in-memory
 * {@link CredentialsProvider} for each fetch / push, never written to {@code .git/config}, a URL, a
 * log or disk. Not {@code Serializable} on purpose, and {@link #toString()} masks the token.
 */
public final class SessionCredentials
{

    /** The session attribute the credentials live under. */
    public static final String ATTRIBUTE = SessionCredentials.class.getName();

    private final ForgeCredentials credentials;

    public SessionCredentials(ForgeCredentials aCredentials)
    {
        credentials = aCredentials;
    }


    public ForgeCredentials forge()
    {
        return credentials;
    }


    /** A fresh provider for one git operation. */
    public CredentialsProvider git()
    {
        return new UsernamePasswordCredentialsProvider(credentials.login(), credentials.token());
    }


    @Override
    public String toString()
    {
        return "SessionCredentials[login=" + credentials.login() + ", token=***]";
    }
}
