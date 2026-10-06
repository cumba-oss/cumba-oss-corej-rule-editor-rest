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

    private List<String> trustedProxies = List.of();

    private final Brand brand = new Brand();

    public Repository getRepository()
    {
        return repository;
    }


    public Brand getBrand()
    {
        return brand;
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


    /**
     * {@code editor.trusted-proxies}: the reverse proxies (addresses or CIDR blocks) whose
     * {@code X-Forwarded-For} is believed when a per-client limit keys a request (anonymous runs
     * and searches, logins — security review M4). Empty, the default: the TCP peer is the client,
     * so behind a proxy every client shares the proxy's key.
     */
    public List<String> getTrustedProxies()
    {
        return List.copyOf(trustedProxies);
    }


    public void setTrustedProxies(List<String> aTrustedProxies)
    {
        trustedProxies = List.copyOf(aTrustedProxies);
    }


    /** The workspace root as an absolute path. */
    public Path workspaceRootPath()
    {
        return Path.of(workspaceRoot).toAbsolutePath().normalize();
    }


    /**
     * The bound repository, parsed; fails loudly on a missing or malformed URL, and on a transport
     * the installation did not allow (security review L3: {@code https} only, unless
     * {@code allow-http} / {@code allow-file}).
     */
    public RepositoryCoordinates coordinates()
    {
        return RepositoryCoordinates.parse(repository.getUrl(), repository.isAllowHttp(),
                repository.isAllowFile());
    }


    /** The forge API base, checked like the repository URL (security review L3). */
    public String forgeApiUrl()
    {
        return RepositoryCoordinates.checkApiUrl(forge.getApiUrl(), repository.isAllowHttp());
    }

    /** {@code editor.repository.*}. */
    public static class Repository
    {

        private String url = "";

        private @Nullable String defaultBranch;

        private boolean allowHttp;

        private boolean allowFile;

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


        /**
         * {@code editor.repository.allow-http}: a developer run against a forge without TLS may use
         * {@code http://} for the repository and the forge API. Off by default: the login token
         * travels in every git request.
         */
        public boolean isAllowHttp()
        {
            return allowHttp;
        }


        public void setAllowHttp(boolean aAllowHttp)
        {
            allowHttp = aAllowHttp;
        }


        /** {@code editor.repository.allow-file}: a {@code file://} repository — tests only. */
        public boolean isAllowFile()
        {
            return allowFile;
        }


        public void setAllowFile(boolean aAllowFile)
        {
            allowFile = aAllowFile;
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

        private @Nullable String hooksDir;

        private Duration gitTimeout = Duration.ofSeconds(60);

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


        /**
         * {@code editor.workspace.hooks-dir}: the EMPTY directory every clone's
         * {@code core.hooksPath} names (§5.8.3); blank = {@code <workspace-root>/.hooks-disabled}.
         * A container points it at a root-owned, read-only directory of the image (security review
         * L4), so the service user cannot plant a hook there either.
         */
        public @Nullable String getHooksDir()
        {
            return hooksDir;
        }


        public void setHooksDir(@Nullable String aHooksDir)
        {
            hooksDir = aHooksDir;
        }


        /**
         * {@code editor.workspace.git-timeout}: the transport timeout of every clone, fetch, push
         * and ls-remote (security review L11) — a remote that accepts and never answers no longer
         * parks a request thread (and the workspace lock) for ever.
         */
        public Duration getGitTimeout()
        {
            return gitTimeout;
        }


        public void setGitTimeout(Duration aGitTimeout)
        {
            gitTimeout = aGitTimeout;
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

        private int searchesPerMinute = 30;

        private int maxSearchResults = 50;

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


        /**
         * Anonymous searches per minute per client (security review L12); beyond that
         * {@code 429 RATE_LIMITED}. 0 disables the limit.
         */
        public int getSearchesPerMinute()
        {
            return searchesPerMinute;
        }


        public void setSearchesPerMinute(int aSearchesPerMinute)
        {
            searchesPerMinute = aSearchesPerMinute;
        }


        /** The {@code limit} an anonymous search is capped at (security review L12). */
        public int getMaxSearchResults()
        {
            return maxSearchResults;
        }


        public void setMaxSearchResults(int aMaxSearchResults)
        {
            maxSearchResults = aMaxSearchResults;
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

        private int maxReportBytes = 32 * 1024 * 1024;

        private int anonymousMaxConcurrent = 1;

        private int anonymousMaxQueued = 2;

        private Duration anonymousTimeout = Duration.ofSeconds(20);

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


        /**
         * The largest report a worker may answer (security review M5); a worker writing more is
         * killed and the run is {@code ABORTED TOO_LARGE}.
         */
        public int getMaxReportBytes()
        {
            return maxReportBytes;
        }


        public void setMaxReportBytes(int aMaxReportBytes)
        {
            maxReportBytes = aMaxReportBytes;
        }


        /**
         * Anonymous runs have a pool of their own (security review M4): this many workers, beside —
         * never taken from — {@code max-concurrent}.
         */
        public int getAnonymousMaxConcurrent()
        {
            return anonymousMaxConcurrent;
        }


        public void setAnonymousMaxConcurrent(int aAnonymousMaxConcurrent)
        {
            anonymousMaxConcurrent = aAnonymousMaxConcurrent;
        }


        /** Anonymous runs waiting for the anonymous pool; beyond that {@code 503 RUN_BUSY}. */
        public int getAnonymousMaxQueued()
        {
            return anonymousMaxQueued;
        }


        public void setAnonymousMaxQueued(int aAnonymousMaxQueued)
        {
            anonymousMaxQueued = aAnonymousMaxQueued;
        }


        /** The wall-clock timeout of an anonymous run (shorter than {@code timeout}). */
        public Duration getAnonymousTimeout()
        {
            return anonymousTimeout;
        }


        public void setAnonymousTimeout(Duration aAnonymousTimeout)
        {
            anonymousTimeout = aAnonymousTimeout;
        }
    }


    /**
     * {@code editor.limits.*} — the size limits on analysed and run buffers (§5.8.5) and on request
     * bodies, the workspace lock wait, the login rate and the pre-login session lifetime (security
     * review M1, M2, L2, L9).
     */
    public static class Limits
    {

        private int maxBufferBytes = 1024 * 1024;

        private int maxRequestBytes;

        private Duration workspaceLockWait = Duration.ofSeconds(30);

        private int loginsPerMinute = 10;

        private Duration preLoginSessionTimeout = Duration.ofMinutes(15);

        public int getMaxBufferBytes()
        {
            return maxBufferBytes;
        }


        public void setMaxBufferBytes(int aMaxBufferBytes)
        {
            maxBufferBytes = aMaxBufferBytes;
        }


        /** The configured request body limit; 0 (the default) derives it. */
        public int getMaxRequestBytes()
        {
            return maxRequestBytes;
        }


        public void setMaxRequestBytes(int aMaxRequestBytes)
        {
            maxRequestBytes = aMaxRequestBytes;
        }


        /**
         * The request body limit in force (security review M2): the configured one, else four
         * buffers' worth — a run carries three buffers (rule, docs, scenario), JSON-escaped.
         */
        public long effectiveMaxRequestBytes()
        {
            return maxRequestBytes > 0 ? maxRequestBytes : 4L * maxBufferBytes;
        }


        /**
         * How long a request waits for its workspace's lock before it is refused with
         * {@code 503 WORKSPACE_BUSY} (security review M1): no request parks a thread for ever
         * behind another one of the same user.
         */
        public Duration getWorkspaceLockWait()
        {
            return workspaceLockWait;
        }


        public void setWorkspaceLockWait(Duration aWorkspaceLockWait)
        {
            workspaceLockWait = aWorkspaceLockWait;
        }


        /** Login attempts per minute per client (security review L9); 0 disables the limit. */
        public int getLoginsPerMinute()
        {
            return loginsPerMinute;
        }


        public void setLoginsPerMinute(int aLoginsPerMinute)
        {
            loginsPerMinute = aLoginsPerMinute;
        }


        /**
         * The lifetime of a session nobody has logged in to yet (the one {@code GET
         * /api/session/csrf} creates, security review L2); a login raises it to
         * {@code server.servlet.session.timeout}.
         */
        public Duration getPreLoginSessionTimeout()
        {
            return preLoginSessionTimeout;
        }


        public void setPreLoginSessionTimeout(Duration aPreLoginSessionTimeout)
        {
            preLoginSessionTimeout = aPreLoginSessionTimeout;
        }
    }


    /**
     * {@code editor.brand.*}: the look of the bundled SPA (PLAN-web-branding-and-theme §5.5). The
     * brand is a named folder — {@code brand.css} and an optional {@code brand.json} — looked up
     * under {@link #getDirectory() directory} first, then among the brands packaged with the SPA.
     * Read by {@link BrandResourceConfig}; inert in an API-only build.
     */
    public static class Brand
    {

        /**
         * The brand's name; {@code EDITOR_BRAND_NAME} ({@code EDITOR_BRAND} maps to this object and
         * refuses to start).
         */
        private String name = "neutral";

        /**
         * Where extra brands live ({@code <directory>/<name>/brand.css}); blank = packaged only.
         */
        private @Nullable String directory;

        public String getName()
        {
            return name;
        }


        public void setName(String aName)
        {
            name = aName;
        }


        public @Nullable String getDirectory()
        {
            return directory;
        }


        public void setDirectory(@Nullable String aDirectory)
        {
            directory = aDirectory;
        }
    }
}
