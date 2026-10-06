package net.cumba.corej.editor.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RepositoryCoordinatesTest
{

    @Test
    void ownerAndNameAreTheLastTwoSegmentsWithoutDotGit()
    {
        RepositoryCoordinates c = RepositoryCoordinates
                .parse("https://forge.example.test/cumba/cumba-corej-rules.git");
        assertEquals("cumba", c.owner());
        assertEquals("cumba-corej-rules", c.name());
        assertEquals("https://forge.example.test", c.webBase());
        assertEquals("https://forge.example.test/cumba/cumba-corej-rules.git", c.url());
    }


    @Test
    void trailingSlashAndMissingDotGitAreTolerated()
    {
        RepositoryCoordinates c = RepositoryCoordinates
                .parse("https://github.com:8443/cumba-oss/cumba-oss-corej-rules/");
        assertEquals("cumba-oss", c.owner());
        assertEquals("cumba-oss-corej-rules", c.name());
        assertEquals("https://github.com:8443", c.webBase());
    }


    @Test
    void fileUrlsParseTheSameWayWhenAllowed()
    {
        RepositoryCoordinates c = RepositoryCoordinates.parse("file:///tmp/x/acme/rules.git", false,
                true);
        assertEquals("acme", c.owner());
        assertEquals("rules", c.name());
    }


    @Test
    void onlyHttpsUnlessTheInstallationAllowsMore()
    {
        // security review L3: http needs the developer flag, file the test flag
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryCoordinates.parse("http://forge.example.test/acme/rules.git"));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryCoordinates.parse("file:///tmp/x/acme/rules.git"));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryCoordinates.parse("file:///tmp/x/acme/rules.git", true, false));
        assertEquals("acme", RepositoryCoordinates
                .parse("http://forge.example.test/acme/rules.git", true, false).owner());
        assertEquals("https://forge.example.test/api/v1",
                RepositoryCoordinates.checkApiUrl(" https://forge.example.test/api/v1 ", false));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryCoordinates.checkApiUrl("http://forge.example.test/api/v1", false));
        assertEquals("http://127.0.0.1:1/api/v1",
                RepositoryCoordinates.checkApiUrl("http://127.0.0.1:1/api/v1", true));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryCoordinates.checkApiUrl("file:///api/v1", true));
    }


    @Test
    void credentialsQueriesAndFragmentsAreRefusedAndNeverEchoed()
    {
        IllegalArgumentException creds = assertThrows(IllegalArgumentException.class,
                () -> RepositoryCoordinates
                        .parse("https://mw:s3cret-token@forge.example.test/acme/rules.git"));
        assertTrue(creds.getMessage().contains("user-info"), creds.getMessage());
        assertFalse(creds.getMessage().contains("s3cret-token"), creds.getMessage());
        assertThrows(IllegalArgumentException.class, () -> RepositoryCoordinates
                .parse("https://forge.example.test/acme/rules.git?ref=x"));
        assertThrows(IllegalArgumentException.class, () -> RepositoryCoordinates
                .parse("https://forge.example.test/acme/rules.git#main"));
        assertThrows(IllegalArgumentException.class, () -> RepositoryCoordinates
                .checkApiUrl("https://token@forge.example.test/api/v1", false));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryCoordinates.parse("https:///acme/rules.git"));
    }


    @Test
    void malformedUrlsFailByName()
    {
        assertThrows(IllegalArgumentException.class, () -> RepositoryCoordinates.parse(""));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryCoordinates.parse("git@forge.example.test:cumba/rules.git"));
        assertThrows(IllegalArgumentException.class,
                () -> RepositoryCoordinates.parse("https://forge.example.test/rules.git"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RepositoryCoordinates.parse("ssh://git@host/a/b.git"));
        assertTrue(e.getMessage().contains("https"), e.getMessage());
    }
}
