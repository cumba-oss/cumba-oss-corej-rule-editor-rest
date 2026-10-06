package net.cumba.corej.editor.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void fileUrlsParseTheSameWay()
    {
        RepositoryCoordinates c = RepositoryCoordinates.parse("file:///tmp/x/acme/rules.git");
        assertEquals("acme", c.owner());
        assertEquals("rules", c.name());
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
        assertTrue(e.getMessage().contains("http(s)"), e.getMessage());
    }
}
