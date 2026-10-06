package net.cumba.corej.editor.testing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.corej.editor.config.RepositoryCoordinates;
import net.cumba.corej.editor.error.Errors;
import net.cumba.corej.editor.forge.ForgeClient;
import net.cumba.corej.editor.forge.ForgeCredentials;
import net.cumba.corej.editor.forge.ForgeUser;
import net.cumba.corej.editor.forge.RepositoryAccess;

/**
 * A forge in memory: tokens map to users, each token has a repository access and a receive-pack
 * answer; anonymous visibility is a switch. The web tests log in against this; the HTTP clients
 * have their own fake server.
 */
public final class FakeForge implements ForgeClient
{

    /** What a token is allowed. */
    public record Grant(ForgeUser user, RepositoryAccess access, boolean receivePack)
    {
    }

    public static final String WRITE_TOKEN = "write-token";

    public static final String READ_TOKEN = "read-token";

    /** A user whose ROLE may push but whose TOKEN is read-scoped (review M4). */
    public static final String ROLE_ONLY_TOKEN = "role-only-token";

    public static final ForgeUser WRITER = new ForgeUser(8, "mw", "Michael", "mw@example.test");

    public static final ForgeUser READER = new ForgeUser(9, "rv", "", "rv@example.test");

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    private volatile boolean anonymouslyReadable;

    private final AtomicInteger receivePackProbes = new AtomicInteger();

    public FakeForge()
    {
        RepositoryAccess push = new RepositoryAccess(true, true, RemoteRepo.DEFAULT_BRANCH, true);
        RepositoryAccess pull = new RepositoryAccess(true, false, RemoteRepo.DEFAULT_BRANCH, true);
        grants.put(WRITE_TOKEN, new Grant(WRITER, push, true));
        grants.put(READ_TOKEN, new Grant(READER, pull, false));
        grants.put(ROLE_ONLY_TOKEN, new Grant(WRITER, push, false));
    }


    public FakeForge grant(String aToken, Grant aGrant)
    {
        grants.put(aToken, aGrant);
        return this;
    }


    public void setAnonymouslyReadable(boolean aReadable)
    {
        anonymouslyReadable = aReadable;
    }


    public int receivePackProbes()
    {
        return receivePackProbes.get();
    }


    private Grant grant(ForgeCredentials aCredentials)
    {
        Grant g = grants.get(aCredentials.token());
        if (g == null || !g.user().login().equals(aCredentials.login()))
        {
            throw Errors.forgeRefused("unknown token");
        }
        return g;
    }


    @Override
    public ForgeUser user(ForgeCredentials aCredentials)
    {
        return grant(aCredentials).user();
    }


    @Override
    public RepositoryAccess repository(ForgeCredentials aCredentials,
            RepositoryCoordinates aRepository)
    {
        return grant(aCredentials).access();
    }


    @Override
    public boolean canReceivePack(ForgeCredentials aCredentials, RepositoryCoordinates aRepository)
    {
        receivePackProbes.incrementAndGet();
        return grant(aCredentials).receivePack();
    }


    @Override
    public boolean anonymouslyReadable(RepositoryCoordinates aRepository)
    {
        return anonymouslyReadable;
    }


    @Override
    public String pullRequestUrl(RepositoryCoordinates aRepository, String aDefaultBranch,
            String aBranch)
    {
        return "fake://pr/" + aDefaultBranch + "..." + aBranch;
    }
}
