package net.cumba.corej.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import net.cumba.corej.editor.config.BuildInfo;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.git.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The context loads with the SHIPPED {@code application.yaml} (only the repository URL and the
 * workspace root pointed at scratch, so no clone of the real repository is ever attempted), and the
 * shipped keys bind to what they document. Guards a renamed or mistyped key, which Spring would
 * otherwise ignore silently.
 */
@SpringBootTest
@ExtendWith(HomeStaysCleanExtension.class)
class RuleEditorApplicationTest
{

    @TempDir
    private static Path root;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry aRegistry)
    {
        aRegistry.add("editor.repository.url", () -> "file://" + root.resolve("acme/rules.git"));
        aRegistry.add("editor.workspace-root", () -> root.resolve("workspaces").toString());
    }

    @Autowired
    private EditorProperties properties;

    @Autowired
    private WorkspaceService workspaces;

    @Autowired
    private Environment environment;

    @Test
    void shippedConfigurationBinds()
    {
        assertEquals(EditorProperties.ForgeType.GITEA, properties.getForge().getType());
        assertEquals("https://forge.example.org/api/v1", properties.getForge().getApiUrl());
        assertEquals(90, properties.getWorkspace().getRetentionDays());
        assertEquals(Duration.ofHours(6), properties.getWorkspace().getRetentionInterval());
        assertFalse(properties.getAnonymous().isEnabled());
        assertEquals(Duration.ofMinutes(10), properties.getAnonymous().getRefreshInterval());
        assertTrue(properties.getRepository().configuredDefaultBranch().isEmpty(),
                "a blank default-branch means: the forge decides");
        assertEquals("acme", properties.coordinates().owner());
        assertTrue(Files.isDirectory(workspaces.root().resolve(".hooks-disabled")));
        assertEquals("corej-rule-editor-rest", environment.getProperty("spring.application.name"));
    }


    @Test
    void sessionHardeningIsConfigured()
    {
        // §5.1.4: never persisted, HttpOnly, Secure, SameSite=Lax, no actuator at all
        assertEquals("false", environment.getProperty("server.servlet.session.persistent"));
        assertEquals("true", environment.getProperty("server.servlet.session.cookie.http-only"));
        assertEquals("true", environment.getProperty("server.servlet.session.cookie.secure"));
        assertEquals("lax", environment.getProperty("server.servlet.session.cookie.same-site"));
        assertEquals("true", environment.getProperty("spring.mvc.problemdetails.enabled"));
        assertFalse(classPresent("org.springframework.boot.actuate.endpoint.Endpoint"),
                "no actuator on the classpath, so no heapdump / env endpoint can exist");
    }


    @Test
    void buildInfoIsFilteredInAMavenRun()
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("projectBasedir") != null,
                "not a Maven run");
        assertNotEquals(BuildInfo.UNKNOWN, BuildInfo.current().version());
        assertTrue(BuildInfo.current().version().matches("\\d.*"), BuildInfo.current().version());
    }


    private static boolean classPresent(String aName)
    {
        try
        {
            Class.forName(aName);
            return true;
        }
        catch (ClassNotFoundException e)
        {
            return false;
        }
    }
}
