package net.cumba.corej.editor.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.editor.config.RepositoryCoordinates;
import net.cumba.corej.editor.error.EditorException;
import net.cumba.corej.editor.testing.FakeForgeServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The GitHub client against a fake server; the email fallback chain is the documented one. */
class GitHubClientTest
{

    private static final ForgeCredentials CREDS = new ForgeCredentials("octo", "ghp_x");

    private FakeForgeServer server;

    private GitHubClient client;

    @BeforeEach
    void start() throws IOException
    {
        server = new FakeForgeServer();
        client = new GitHubClient(server.baseUrl());
    }


    @AfterEach
    void stop()
    {
        server.close();
    }


    @Test
    void userWithPublicEmailSendsTheDocumentedHeaders()
    {
        server.on("/user", 200,
                "{\"id\":42,\"login\":\"octo\",\"name\":\"Octo Cat\",\"email\":\"o@x.test\"}");
        ForgeUser u = client.user(CREDS);
        assertEquals(42, u.id());
        assertEquals("Octo Cat", u.name());
        assertEquals("o@x.test", u.email());
        FakeForgeServer.Seen seen = server.seen().get(0);
        assertEquals("Bearer ghp_x", seen.header("Authorization"));
        assertEquals("application/vnd.github+json", seen.header("Accept"));
        assertEquals("2022-11-28", seen.header("X-github-api-version"));
        assertEquals(1, server.seen().size(), "no /user/emails call when the email is public");
    }


    @Test
    void nullEmailFallsBackToThePrimaryVerifiedAddress()
    {
        server.on("/user", 200, "{\"id\":42,\"login\":\"octo\",\"name\":null,\"email\":null}");
        server.on("/user/emails", 200,
                "[{\"email\":\"old@x.test\",\"verified\":true,"
                        + "\"primary\":false},{\"email\":\"un@x.test\",\"verified\":false,"
                        + "\"primary\":true},{\"email\":\"me@x.test\",\"verified\":true,"
                        + "\"primary\":true}]");
        ForgeUser u = client.user(CREDS);
        assertEquals("octo", u.name(), "a null name falls back to the login");
        assertEquals("me@x.test", u.email());
    }


    @Test
    void noPrimaryVerifiedAddressTakesTheFirstVerifiedOne()
    {
        server.on("/user", 200, "{\"id\":42,\"login\":\"octo\"}");
        server.on("/user/emails", 200,
                "[{\"email\":\"a@x.test\",\"verified\":true,"
                        + "\"primary\":false},{\"email\":\"b@x.test\",\"verified\":true,"
                        + "\"primary\":false}]");
        assertEquals("a@x.test", client.user(CREDS).email());
    }


    @Test
    void noEmailAtAllIsTheNoreplyAddress()
    {
        server.on("/user", 200, "{\"id\":42,\"login\":\"octo\"}");
        server.on("/user/emails", 403, "{\"message\":\"scope\"}");
        assertEquals("42+octo@users.noreply.github.com", client.user(CREDS).email());
        server.on("/user/emails", 200, "[]");
        assertEquals("42+octo@users.noreply.github.com", client.user(CREDS).email());
    }


    @Test
    void repositoryAndProbesMatchTheGiteaShape()
    {
        RepositoryCoordinates repo = RepositoryCoordinates
                .parse(server.baseUrl() + "/acme/rules.git", true, false);
        server.on("/repos/acme/rules", 200, "{\"default_branch\":\"main\",\"private\":false,"
                + "\"permissions\":{\"admin\":true,\"push\":true,\"pull\":true}}");
        RepositoryAccess a = client.repository(CREDS, repo);
        assertTrue(a.push());
        assertFalse(a.privateRepository());
        assertEquals("main", a.defaultBranch());
        server.on("/repos/acme/rules", 200, "{\"permissions\":{\"pull\":true}}");
        assertEquals("FORGE_UNAVAILABLE",
                assertThrows(EditorException.class, () -> client.repository(CREDS, repo)).code(),
                "no default branch");
        server.on("/acme/rules.git/info/refs?service=git-receive-pack", 401, "");
        assertFalse(client.canReceivePack(CREDS, repo));
        assertEquals("https://github.com/cumba-oss/x/compare/main...b?expand=1",
                client.pullRequestUrl(
                        RepositoryCoordinates.parse("https://github.com/cumba-oss/x.git"), "main",
                        "b"));
    }
}
