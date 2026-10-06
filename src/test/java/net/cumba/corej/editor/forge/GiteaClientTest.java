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

/**
 * The Gitea client against a fake server answering what the live Gitea answered on 2026-10-06
 * (phase 0): an empty {@code full_name}, {@code permissions} with {@code push:false} for a read
 * token, 404 for an invisible repository, and 403 on the receive-pack advertisement for a read
 * token. The write-token side (200) is what the owner's test tokens will confirm later (§8.4).
 */
class GiteaClientTest
{

    private static final ForgeCredentials CREDS = new ForgeCredentials("mw", "t0k3n");

    private FakeForgeServer server;

    private GiteaClient client;

    private RepositoryCoordinates repo;

    @BeforeEach
    void start() throws IOException
    {
        server = new FakeForgeServer();
        client = new GiteaClient(server.baseUrl() + "/api/v1/");
        repo = RepositoryCoordinates.parse(server.baseUrl() + "/acme/rules.git", true, false);
    }


    @AfterEach
    void stop()
    {
        server.close();
    }


    @Test
    void userFallsBackToTheLoginWhenFullNameIsEmpty()
    {
        server.on("/api/v1/user", 200,
                "{\"id\":8,\"login\":\"mw\",\"full_name\":\"\",\"email\":\"mw@example.test\"}");
        ForgeUser u = client.user(CREDS);
        assertEquals(8, u.id());
        assertEquals("mw", u.login());
        assertEquals("mw", u.name(), "an empty full_name falls back to the login");
        assertEquals("mw@example.test", u.email());
        FakeForgeServer.Seen seen = server.seen().get(0);
        assertEquals("token t0k3n", seen.header("Authorization"));
        assertEquals("GET", seen.method());
    }


    @Test
    void userWithoutEmailGetsANoreplyAddress()
    {
        server.on("/api/v1/user", 200, "{\"id\":8,\"login\":\"mw\",\"full_name\":\"M W\"}");
        ForgeUser u = client.user(CREDS);
        assertEquals("M W", u.name());
        assertEquals("mw@noreply.127.0.0.1", u.email());
    }


    @Test
    void repositoryReportsPermissionsAndDefaultBranch()
    {
        server.on("/api/v1/repos/acme/rules", 200, "{\"default_branch\":\"main\",\"private\":true,"
                + "\"permissions\":{\"admin\":false,\"push\":false,\"pull\":true}}");
        RepositoryAccess a = client.repository(CREDS, repo);
        assertTrue(a.pull());
        assertFalse(a.push());
        assertEquals("main", a.defaultBranch());
        assertTrue(a.privateRepository());
    }


    @Test
    void invisibleRepositoryIs404AndReadsAsRefused()
    {
        server.on("/api/v1/repos/acme/rules", 404, "{\"message\":\"not found\"}");
        EditorException e = assertThrows(EditorException.class,
                () -> client.repository(CREDS, repo));
        assertEquals("FORGE_REFUSED", e.code());
        assertEquals(401, e.status().value());
    }


    @Test
    void badTokenIs401AndReadsAsRefused()
    {
        server.on("/api/v1/user", 401, "{\"message\":\"token is required\"}");
        assertEquals("FORGE_REFUSED",
                assertThrows(EditorException.class, () -> client.user(CREDS)).code());
    }


    @Test
    void serverErrorsAndNonJsonAreUnavailable()
    {
        server.on("/api/v1/user", 500, "boom");
        assertEquals("FORGE_UNAVAILABLE",
                assertThrows(EditorException.class, () -> client.user(CREDS)).code());
        server.on("/api/v1/user", 200, "<html>");
        assertEquals("FORGE_UNAVAILABLE",
                assertThrows(EditorException.class, () -> client.user(CREDS)).code());
        server.on("/api/v1/user", 200, "{\"login\":\"mw\"}");
        assertEquals("FORGE_UNAVAILABLE",
                assertThrows(EditorException.class, () -> client.user(CREDS)).code(),
                "no numeric id");
    }


    @Test
    void receivePackProbeIsTrueOnlyFor200AndSendsBasicAuth()
    {
        String path = "/acme/rules.git/info/refs?service=git-receive-pack";
        server.on(path, 403, "");
        assertFalse(client.canReceivePack(CREDS, repo), "403: a read token");
        assertEquals(CREDS.basicAuthorization(), server.seen().get(0).header("Authorization"));
        assertEquals("Basic bXc6dDBrM24=", CREDS.basicAuthorization());
        server.on(path, 200, "001f# service=git-receive-pack\n0000");
        assertTrue(client.canReceivePack(CREDS, repo), "200: a write token");
        server.on(path, 401, "");
        assertFalse(client.canReceivePack(CREDS, repo));
    }


    @Test
    void anonymousProbeIsTrueOnlyFor200WithoutAuth()
    {
        String path = "/acme/rules.git/info/refs?service=git-upload-pack";
        server.on(path, 200, "001e# service=git-upload-pack\n0000");
        assertTrue(client.anonymouslyReadable(repo));
        assertEquals("", server.seen().get(0).header("Authorization"));
        server.on(path, 404, "");
        assertFalse(client.anonymouslyReadable(repo));
    }


    @Test
    void anUnreachableForgeIsNotAnonymouslyReadable() throws IOException
    {
        String base;
        try (FakeForgeServer gone = new FakeForgeServer())
        {
            base = gone.baseUrl();
        }
        GiteaClient c = new GiteaClient(base + "/api/v1");
        assertFalse(
                c.anonymouslyReadable(RepositoryCoordinates.parse(base + "/a/b.git", true, false)));
        assertEquals("FORGE_UNAVAILABLE",
                assertThrows(EditorException.class, () -> c.user(CREDS)).code());
    }


    @Test
    void pullRequestUrlIsTheCompareView()
    {
        RepositoryCoordinates r = RepositoryCoordinates
                .parse("https://forge.example.test/cumba/cumba-corej-rules.git");
        assertEquals("https://forge.example.test/cumba/cumba-corej-rules/compare/main...fix-x",
                client.pullRequestUrl(r, "main", "fix-x"));
    }


    @Test
    void credentialsNeverPrintTheToken()
    {
        assertFalse(CREDS.toString().contains("t0k3n"), CREDS.toString());
        assertThrows(IllegalArgumentException.class, () -> new ForgeCredentials("mw", " "));
        assertThrows(IllegalArgumentException.class, () -> new GiteaClient(" "));
    }
}
