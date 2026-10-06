package net.cumba.corej.editor.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The installation configuration under {@code editor.*} (PLAN-rule-editor §5.1.1): the one
 * repository this instance serves, the forge behind it, where the per-user workspaces live, the
 * retention sweep and the optional anonymous read mode. Values and their meaning are documented in
 * {@code application.yaml}.
 */
@ConfigurationProperties("editor")
public class EditorProperties
{

    /** {@code editor.forge.type}. */
    public enum ForgeType
    {
        GITEA, GITHUB
    }

    private final Repository repository = new Repository();

    private final Forge forge = new Forge();

    private String workspaceRoot = "./workspaces";

    private final Workspace workspace = new Workspace();

    private final Anonymous anonymous = new Anonymous();

    private final Cors cors = new Cors();

    private @Nullable String metadataStore;

    private final Run run = new Run();

    private final Limits limits = new Limits();

    public Repository getRepository()
    {
        return repository;
    }


    public Forge getForge()
    {
        return forge;
    }


    public String getWorkspaceRoot()
    {
        return workspaceRoot;
    }


    public void setWorkspaceRoot(String aWorkspaceRoot)
    {
        workspaceRoot = aWorkspaceRoot;
    }


    public Workspace getWorkspace()
    {
        return workspace;
    }


    public Anonymous getAnonymous()
    {
        return anonymous;
    }


    public Cors getCors()
    {
        return cors;
    }


    /**
     * {@code editor.metadata-store}: the metadata store file (PLAN-rule-editor §5.1.1). Blank means
     * the engine's own resolution, {@code CDISC_METADATA_STORE} then
     * {@code -Dcdisc.metadata.store}.
     */
    public @Nullable String getMetadataStore()
    {
        return metadataStore;
    }


    public void setMetadataStore(@Nullable String aMetadataStore)
    {
        metadataStore = aMetadataStore;
    }


    public Run getRun()
    {
        return run;
    }


    public Limits getLimits()
    {
        return limits;
    }


    /** The workspace root as an absolute path. */
    public Path workspaceRootPath()
    {
        return Path.of(workspaceRoot).toAbsolutePath().normalize();
    }


    /** The bound repository, parsed; fails loudly on a missing or malformed URL. */
    public RepositoryCoordinates coordinates()
    {
        return RepositoryCoordinates.parse(repository.getUrl());
    }

    /** {@code editor.repository.*}. */
    public static class Repository
    {

        private String url = "";

        private @Nullable String defaultBranch;

        public String getUrl()
        {
            return url;
        }


        public void setUrl(String aUrl)
        {
            url = aUrl;
        }


        public @Nullable String getDefaultBranch()
        {
            return defaultBranch;
        }


        public void setDefaultBranch(@Nullable String aDefaultBranch)
        {
            defaultBranch = aDefaultBranch;
        }


        /** The configured default branch, absent when blank. */
        public Optional<String> configuredDefaultBranch()
        {
            String b = defaultBranch;
            return b == null || b.isBlank() ? Optional.empty() : Optional.of(b.strip());
        }
    }


    /** {@code editor.forge.*}. */
    public static class Forge
    {

        private ForgeType type = ForgeType.GITEA;

        private String apiUrl = "";

        public ForgeType getType()
        {
            return type;
        }


        public void setType(ForgeType aType)
        {
            type = aType;
        }


        public String getApiUrl()
        {
            return apiUrl;
        }


        public void setApiUrl(String aApiUrl)
        {
            apiUrl = aApiUrl;
        }
    }


    /** {@code editor.workspace.*}. */
    public static class Workspace
    {

        private int retentionDays = 90;

        private Duration retentionInterval = Duration.ofHours(6);

        public int getRetentionDays()
        {
            return retentionDays;
        }


        public void setRetentionDays(int aRetentionDays)
        {
            retentionDays = aRetentionDays;
        }


        public Duration getRetentionInterval()
        {
            return retentionInterval;
        }


        public void setRetentionInterval(Duration aRetentionInterval)
        {
            retentionInterval = aRetentionInterval;
        }
    }


    /**
     * {@code editor.cors.*} — the allow-list for the two-apps-on-sibling-subdomains shape
     * (PLAN-rule-editor §4.2). Empty, the default, means no cross-origin request is answered: the
     * bundled SPA and the reverse-proxy shape are same-origin. A cross-site origin cannot work (the
     * session cookie is {@code SameSite=Lax}) and is not what this list is for.
     */
    public static class Cors
    {

        private List<String> allowedOrigins = List.of();

        /** An immutable copy: exact origins, in the configured order. */
        public List<String> getAllowedOrigins()
        {
            return List.copyOf(allowedOrigins);
        }


        public void setAllowedOrigins(List<String> aAllowedOrigins)
        {
            allowedOrigins = List.copyOf(aAllowedOrigins);
        }
    }


    /** {@code editor.anonymous.*}. */
    public static class Anonymous
    {

        private boolean enabled;

        private Duration refreshInterval = Duration.ofMinutes(10);

        public boolean isEnabled()
        {
            return enabled;
        }


        public void setEnabled(boolean aEnabled)
        {
            enabled = aEnabled;
        }


        public Duration getRefreshInterval()
        {
            return refreshInterval;
        }


        public void setRefreshInterval(Duration aRefreshInterval)
        {
            refreshInterval = aRefreshInterval;
        }
    }


    /**
     * {@code editor.run.*} — the run workers (PLAN-rule-editor §5.8.5, review H5): every run is a
     * child JVM with a hard heap cap, killed at the wall-clock timeout; a bounded queue answers 503
     * beyond its limit; anonymous mode adds a per-IP rate limit.
     */
    public static class Run
    {

        private String maxHeap = "1g";

        private Duration timeout = Duration.ofSeconds(60);

        private int maxConcurrent = 2;

        private int maxQueued = 4;

        private Duration queueWait = Duration.ofSeconds(30);

        private int anonymousRunsPerMinute = 6;

        private @Nullable String classpath;

        private @Nullable String javaCommand;

        private int workerLogBytes = 16 * 1024;

        public String getMaxHeap()
        {
            return maxHeap;
        }


        public void setMaxHeap(String aMaxHeap)
        {
            maxHeap = aMaxHeap;
        }


        public Duration getTimeout()
        {
            return timeout;
        }


        public void setTimeout(Duration aTimeout)
        {
            timeout = aTimeout;
        }


        public int getMaxConcurrent()
        {
            return maxConcurrent;
        }


        public void setMaxConcurrent(int aMaxConcurrent)
        {
            maxConcurrent = aMaxConcurrent;
        }


        public int getMaxQueued()
        {
            return maxQueued;
        }


        public void setMaxQueued(int aMaxQueued)
        {
            maxQueued = aMaxQueued;
        }


        public Duration getQueueWait()
        {
            return queueWait;
        }


        public void setQueueWait(Duration aQueueWait)
        {
            queueWait = aQueueWait;
        }


        public int getAnonymousRunsPerMinute()
        {
            return anonymousRunsPerMinute;
        }


        public void setAnonymousRunsPerMinute(int aAnonymousRunsPerMinute)
        {
            anonymousRunsPerMinute = aAnonymousRunsPerMinute;
        }


        /**
         * The worker's class path; blank derives it from this JVM ({@code bootstrap.dir}/lib/*
         * under the bootstrap launcher, else {@code java.class.path}).
         */
        public @Nullable String getClasspath()
        {
            return classpath;
        }


        public void setClasspath(@Nullable String aClasspath)
        {
            classpath = aClasspath;
        }


        /** The {@code java} to start; blank uses this JVM's own. */
        public @Nullable String getJavaCommand()
        {
            return javaCommand;
        }


        public void setJavaCommand(@Nullable String aJavaCommand)
        {
            javaCommand = aJavaCommand;
        }


        public int getWorkerLogBytes()
        {
            return workerLogBytes;
        }


        public void setWorkerLogBytes(int aWorkerLogBytes)
        {
            workerLogBytes = aWorkerLogBytes;
        }
    }


    /** {@code editor.limits.*} — the size limits on analysed and run buffers (§5.8.5). */
    public static class Limits
    {

        private int maxBufferBytes = 1024 * 1024;

        public int getMaxBufferBytes()
        {
            return maxBufferBytes;
        }


        public void setMaxBufferBytes(int aMaxBufferBytes)
        {
            maxBufferBytes = aMaxBufferBytes;
        }
    }
}
