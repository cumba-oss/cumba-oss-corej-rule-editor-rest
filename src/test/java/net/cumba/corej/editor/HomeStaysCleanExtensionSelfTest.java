package net.cumba.corej.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

/**
 * The negative control of {@link HomeStaysCleanExtension}: a test that writes into the watched home
 * MUST red, and the guard must name what changed. Run against a temp directory standing in for the
 * real home, so the control itself never touches {@code ~/.cumbaDataBrowser}.
 */
class HomeStaysCleanExtensionSelfTest
{

    @Test
    void aWriteIntoTheWatchedHomeIsRedAndNamed(@TempDir Path aFakeHome) throws IOException
    {
        String saved = System.getProperty(HomeStaysCleanExtension.REAL_HOME_PROPERTY);
        System.setProperty(HomeStaysCleanExtension.REAL_HOME_PROPERTY, aFakeHome.toString());
        try
        {
            HomeStaysCleanExtension guard = new HomeStaysCleanExtension();
            guard.beforeEach(null);
            Path leak = Files.createDirectories(
                    aFakeHome.resolve(".cumbaDataBrowser").resolve("dictionaries"));
            AssertionFailedError red = assertThrows(AssertionFailedError.class,
                    () -> guard.afterEach(null), "a created directory must red the guard");
            assertTrue(red.getMessage().contains("created " + leak), red.getMessage());

            Path file = Files.writeString(leak.resolve("x.txt"), "1");
            guard.beforeEach(null);
            guard.afterEach(null);
            Files.writeString(file, "22");
            AssertionFailedError changed = assertThrows(AssertionFailedError.class,
                    () -> guard.afterEach(null));
            assertTrue(changed.getMessage().contains("changed " + file), changed.getMessage());
        }
        finally
        {
            if (saved == null)
            {
                System.clearProperty(HomeStaysCleanExtension.REAL_HOME_PROPERTY);
            }
            else
            {
                System.setProperty(HomeStaysCleanExtension.REAL_HOME_PROPERTY, saved);
            }
        }
    }


    @Test
    void theRealHomeFallsBackToUserHomeUnlessThatIsTheRedirect(@TempDir Path aHome)
    {
        assertEquals(aHome.toAbsolutePath(), HomeStaysCleanExtension.realHome(aHome.toString(),
                aHome.resolve("target").resolve("test-home").toString()));
        assertEquals(aHome.toAbsolutePath(),
                HomeStaysCleanExtension.realHome(null, aHome.toString()));
        AssertionFailedError lost = assertThrows(AssertionFailedError.class,
                () -> HomeStaysCleanExtension.realHome(null,
                        aHome.resolve("target").resolve("test-home").toString()));
        assertTrue(lost.getMessage().contains("realUserHome"), lost.getMessage());
    }


    @Test
    void userHomeIsRedirectedToTargetTestHome()
    {
        // A Maven run only: the redirect is a pom control an IDE run cannot set.
        Assumptions.assumeTrue(System.getProperty("projectBasedir") != null, "not a Maven run");
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath();
        assertEquals("test-home", home.getFileName().toString(),
                "user.home must be <module>/target/test-home in a test JVM, but is " + home);
        assertTrue(!home.equals(HomeStaysCleanExtension.realHome()),
                "user.home must not be the real home " + HomeStaysCleanExtension.realHome());
    }
}
