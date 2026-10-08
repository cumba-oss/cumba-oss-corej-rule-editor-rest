package net.cumba.corej.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.corej.editor.config.EditorProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

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
        // PLAN-web-branding-and-theme: the SPA's brand is selectable, neutral by default (R5)
        assertTrue(compose.contains("BRAND: ${BRAND:-neutral}"),
                "rule-editor-web takes BRAND, defaulting to neutral");
    }


    @Test
    @SuppressWarnings("unchecked")
    void theComposeFilePassesTheStoreSeedSettingsThrough() throws IOException
    {
        // owner 2026-10-08: the container seeds its store at start-up; the defaults match
        // application.yaml's, the key defaults to empty (never a value in the file)
        Map<String, Object> compose = new Yaml().load(read("docker-compose.yml"));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> rest = (Map<String, Object>) services.get("rule-editor-rest");
        Map<String, Object> env = (Map<String, Object>) rest.get("environment");
        assertEquals("${EDITOR_STORE_SEED_TYPE:-GITHUB_PKL}", env.get("EDITOR_STORE_SEED_TYPE"));
        assertEquals("${EDITOR_STORE_SEED_PKL_TAG:-v0.17.1}", env.get("EDITOR_STORE_SEED_PKL_TAG"));
        assertEquals("${EDITOR_STORE_SEED_API_KEY:-}", env.get("EDITOR_STORE_SEED_API_KEY"));
        assertEquals("v0.17.1", EditorProperties.Seed.DEFAULT_PKL_TAG);
    }


    @Test
    @SuppressWarnings("unchecked")
    void theComposeFileTrustsOnlyItsPinnedNetwork() throws IOException
    {
        // review: the default trusted every private range, so a client inside one could forge
        // X-Forwarded-For and get a fresh limit key per request
        Map<String, Object> compose = new Yaml().load(read("docker-compose.yml"));
        Map<String, Object> networks = (Map<String, Object>) compose.get("networks");
        Map<String, Object> editor = (Map<String, Object>) networks.get("editor");
        Map<String, Object> ipam = (Map<String, Object>) editor.get("ipam");
        List<Map<String, Object>> config = (List<Map<String, Object>>) ipam.get("config");
        String subnet = String.valueOf(config.get(0).get("subnet"));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> rest = (Map<String, Object>) services.get("rule-editor-rest");
        Map<String, Object> env = (Map<String, Object>) rest.get("environment");
        Matcher m = Pattern.compile("\\$\\{EDITOR_TRUSTED_PROXIES:-(.*)}")
                .matcher(String.valueOf(env.get("EDITOR_TRUSTED_PROXIES")));
        assertTrue(m.matches(), String.valueOf(env.get("EDITOR_TRUSTED_PROXIES")));
        assertEquals(subnet, m.group(1), "the default trusts exactly the pinned subnet");
        for (String service : List.of("rule-editor-rest", "rule-editor-web"))
        {
            assertEquals(List.of("editor"),
                    ((Map<String, Object>) services.get(service)).get("networks"),
                    service + " joins the pinned network");
        }
    }
}
