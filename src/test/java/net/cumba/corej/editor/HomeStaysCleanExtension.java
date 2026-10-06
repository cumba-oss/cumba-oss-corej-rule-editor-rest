package net.cumba.corej.editor;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails — naming every path — any test that lets the code under test write into the REAL user
 * home's {@code .cumbaDataBrowser} tree. Copied from {@code cumba-corej-rest}: every repo of the
 * stack redirects {@code user.home} to {@code target/test-home} in Surefire AND in the pitest
 * {@code jvmArgs}, and this extension is the guard that reds if that redirect is ever lost or
 * bypassed. The real home is read from {@code -DrealUserHome=${user.home}}, passed by the pom from
 * the Maven JVM. {@link HomeStaysCleanExtensionSelfTest} is the negative control.
 */
public final class HomeStaysCleanExtension implements BeforeEachCallback, AfterEachCallback
{

    /** The system property the pom passes: the Maven JVM's {@code user.home}. */
    static final String REAL_HOME_PROPERTY = "realUserHome";

    /** The tail every pom's redirect ends in; a {@code user.home} ending in it IS the redirect. */
    static final Path REDIRECT_TAIL = Path.of("target", "test-home");

    private final Map<Path, String> before = new TreeMap<>();

    static Path realHome()
    {
        return realHome(System.getProperty(REAL_HOME_PROPERTY), System.getProperty("user.home"));
    }


    /** The pure form of {@link #realHome()}, for the self-test's two branches. */
    static Path realHome(@Nullable String aRealUserHome, String aUserHome)
    {
        if (aRealUserHome != null && !aRealUserHome.isBlank())
        {
            return Path.of(aRealUserHome).toAbsolutePath();
        }
        Path home = Path.of(aUserHome).toAbsolutePath();
        if (home.endsWith(REDIRECT_TAIL))
        {
            fail("user.home is the test redirect (" + home + ") but -D" + REAL_HOME_PROPERTY
                    + " is not set: the pom passes the Maven JVM's user.home to every test JVM"
                    + " (Surefire systemPropertyVariables AND the pitest jvmArgs) so this guard"
                    + " knows which home is the REAL one; restore that property");
        }
        return home;
    }


    static Map<Path, String> snapshot() throws IOException
    {
        Map<Path, String> state = new TreeMap<>();
        Path dir = realHome().resolve(".cumbaDataBrowser");
        if (!Files.exists(dir))
        {
            return state;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            for (Path p : walk.toList())
            {
                state.put(p, Files.isDirectory(p) ? "dir"
                        : Files.size(p) + "@" + Files.getLastModifiedTime(p));
            }
        }
        return state;
    }


    @Override
    public void beforeEach(@Nullable ExtensionContext aContext) throws IOException
    {
        before.clear();
        before.putAll(snapshot());
    }


    @Override
    public void afterEach(@Nullable ExtensionContext aContext) throws IOException
    {
        Map<Path, String> after = snapshot();
        List<String> changes = new ArrayList<>();
        for (Map.Entry<Path, String> entry : after.entrySet())
        {
            String was = before.get(entry.getKey());
            if (was == null)
            {
                changes.add("created " + entry.getKey() + " [" + entry.getValue() + "]");
            }
            else if (!was.equals(entry.getValue()))
            {
                changes.add("changed " + entry.getKey() + " [" + was + "] -> [" + entry.getValue()
                        + "]");
            }
        }
        for (Path gone : before.keySet())
        {
            if (!after.containsKey(gone))
            {
                changes.add("removed " + gone);
            }
        }
        if (!changes.isEmpty())
        {
            String name = aContext == null ? "<self-test>" : aContext.getDisplayName();
            fail(name + " wrote into the REAL user home (" + realHome() + "): "
                    + String.join("; ", changes)
                    + ". Test JVMs must resolve user.home to target/test-home (Surefire"
                    + " systemPropertyVariables AND the pitest jvmArgs); a test that reaches an"
                    + " app default must set an explicit target or run under that redirect.");
        }
    }
}
