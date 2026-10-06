package net.cumba.corej.editor.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * This build's identity from the filtered {@code version.properties} (Maven's {@code ${revision}}
 * and git-commit-id's values). {@code "unknown"} when the file is missing or unfiltered — which
 * {@code InfoControllerTest} catches by name, as in {@code cumba-oss-corej-rest}.
 */
public final class BuildInfo
{

    /** This module's artifact id; must track {@code <artifactId>} in the pom. */
    public static final String ARTIFACT_ID = "cumba-oss-corej-rule-editor-rest";

    public static final String UNKNOWN = "unknown";

    private static final BuildInfo INSTANCE = load();

    private final String version;

    private final String commit;

    private BuildInfo(String aVersion, String aCommit)
    {
        version = aVersion;
        commit = aCommit;
    }


    public static BuildInfo current()
    {
        return INSTANCE;
    }


    private static BuildInfo load()
    {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/version.properties"))
        {
            if (in != null)
            {
                p.load(in);
            }
        }
        catch (IOException e)
        {
            return new BuildInfo(UNKNOWN, UNKNOWN);
        }
        return new BuildInfo(filtered(p.getProperty("version")),
                filtered(p.getProperty("gitCommitHashShort")));
    }


    /** A still-unfiltered {@code ${...}} placeholder reads as unknown, never as a version. */
    private static String filtered(String aValue)
    {
        if (aValue == null || aValue.isBlank() || aValue.startsWith("${"))
        {
            return UNKNOWN;
        }
        return aValue;
    }


    public String version()
    {
        return version;
    }


    public String commit()
    {
        return commit;
    }
}
