package net.cumba.corej.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The container defaults are secure (security review M3, L4) — a shape check of the two files,
 * since this sandbox builds no image: the session cookie stays {@code Secure} (no plain-HTTP
 * opt-out in the image's command), the published port binds the loopback only, and the hooks
 * directory every clone points at is a root-owned, read-only directory of the image.
 */
class DeploymentDefaultsTest
{

    static String read(String aFile) throws IOException
    {
        String basedir = System.getProperty("projectBasedir");
        Assumptions.assumeTrue(basedir != null, "not a Maven run");
        return Files.readString(Path.of(basedir).resolve(aFile), StandardCharsets.UTF_8);
    }


    @Test
    void theImageKeepsTheCookieSecureAndTheHooksReadOnly() throws IOException
    {
        String dockerfile = read("Dockerfile");
        String cmd = dockerfile.substring(dockerfile.indexOf("\nCMD "));
        assertFalse(cmd.contains("cookie.secure=false"), "the image's command relaxes nothing");
        assertTrue(cmd.contains("--editor.workspace.hooks-dir=/opt/rule-editor/hooks-disabled"),
                cmd);
        assertTrue(dockerfile.contains("chmod 0555 /opt/rule-editor/hooks-disabled"),
                "the hooks directory is read-only for the service user");
    }


    @Test
    void theComposeFilePublishesTheLoopbackOnly() throws IOException
    {
        String compose = read("docker-compose.yml");
        assertTrue(compose.contains("\"127.0.0.1:${WEB_PORT:-8080}:80\""), compose);
        assertFalse(compose.contains("\"${WEB_PORT:-8080}:80\""), "never every interface");
        assertTrue(compose.contains("SERVER_SERVLET_SESSION_COOKIE_SECURE: ${COOKIE_SECURE:-true}"),
                "the trial opt-out is explicit and off by default");
    }
}
