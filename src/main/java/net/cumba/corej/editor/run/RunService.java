package net.cumba.corej.editor.run;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.cumba.corej.core.VersionInfo;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.StandardRef;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.corpus.CorpusLayout;
import net.cumba.corej.editor.engine.CorpusText;
import net.cumba.corej.editor.engine.EngineStore;
import net.cumba.corej.editor.engine.Json;
import net.cumba.corej.editor.engine.PackageStandards;
import net.cumba.corej.editor.engine.RuleAnalyser;
import net.cumba.corej.editor.error.EditorException;
import net.cumba.corej.editor.git.CorpusSource;
import net.cumba.corej.editor.git.WorkspaceService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Runs as child JVM workers (PLAN-rule-editor §5.8.5, review H5). A run's inputs are the two
 * buffers (rule, scenario) and the scenario's suite directory as the view serves it: the
 * directory's other files (the sidecars) are exported into a server-owned scratch tree, the
 * scenario buffer is written where the file is or would be, and the worker's
 * {@code SidecarResolver} is rooted at the exported suites tree — so a {@code ../}, absolute or
 * symlinked sidecar path has nothing to reach and the engine refuses it (§5.8.6). The worker never
 * sees the workspace: a read-only user, an anonymous reader and an unsaved buffer all run the same
 * way.
 *
 * <p>
 * A run has three steps, and only the first holds the workspace lock (security review M1, REST
 * review #1): {@link #admit} (one run at a time per runner — a user, or an anonymous client — §5.7;
 * {@code 409 RUN_IN_PROGRESS}; anonymous clients are rate-limited, {@code 429 RATE_LIMITED}),
 * {@link #prepare} (the export — the caller holds the lock), {@link #execute} (a worker slot, the
 * worker, the scratch cleanup — the caller holds NO lock).
 * </p>
 *
 * <p>
 * Limits: {@code editor.run.max-concurrent} workers at once, {@code max-queued} waiting (beyond
 * that {@code 503 RUN_BUSY}), {@code timeout} wall-clock after which the process is killed
 * ({@code ABORTED TIMEOUT}), {@code max-heap} as the worker's {@code -Xmx},
 * {@code max-report-bytes} of answer ({@code ABORTED TOO_LARGE}, security review M5). Anonymous
 * runs have a pool of their own ({@code anonymous-max-concurrent}, {@code anonymous-max-queued},
 * {@code anonymous-timeout}; security review M4), so the public can never take a signed-in author's
 * worker.
 * </p>
 */
@Component
public final class RunService
{

    private static final Logger LOG = LoggerFactory.getLogger(RunService.class);

    static final String RUNS_DIR = ".runs";

    /** The report code of a worker that answered more than {@code max-report-bytes}. */
    static final String TOO_LARGE = "TOO_LARGE";

    /** How long past the timeout a worker's own watchdog halts it, should the parent not. */
    static final Duration WATCHDOG_MARGIN = Duration.ofSeconds(15);

    /**
     * The environment a worker inherits, by upper-cased name (security review L4): what starting a
     * JVM needs, nothing that changes the engine's semantics ({@code MAX_ERRORS_PER_RULE} …) and no
     * {@code JAVA_TOOL_OPTIONS}.
     */
    static final Set<String> WORKER_ENVIRONMENT = Set.of("PATH", "SYSTEMROOT", "WINDIR", "TEMP",
            "TMP", "TMPDIR", "LANG", "LC_ALL", "LC_CTYPE", "TZ");

    private final EditorProperties.Run config;

    private final EngineStore store;

    private final PackageStandards standards;

    private final Path runsRoot;

    private final Pool pool;

    private final Pool anonymousPool;

    private final Set<String> active = ConcurrentHashMap.newKeySet();

    private final RateLimiter anonymousLimit;

    private final @Nullable String engineVersion;

    public RunService(EditorProperties aProperties, EngineStore aStore, PackageStandards aStandards,
            WorkspaceService aWorkspaces)
        throws IOException
    {
        config = aProperties.getRun();
        store = aStore;
        standards = aStandards;
        runsRoot = aWorkspaces.root().resolve(RUNS_DIR);
        Files.createDirectories(runsRoot);
        pool = new Pool("run", config.getMaxConcurrent(), config.getMaxQueued(),
                config.getQueueWait(), config.getTimeout());
        anonymousPool = new Pool("anonymous run", config.getAnonymousMaxConcurrent(),
                config.getAnonymousMaxQueued(), config.getQueueWait(),
                config.getAnonymousTimeout());
        anonymousLimit = new RateLimiter(config.getAnonymousRunsPerMinute(), Duration.ofMinutes(1));
        engineVersion = engineVersion();
    }


    /** The engine's version, read from the jar that holds {@code Rule} (either twin's name). */
    static @Nullable String engineVersion()
    {
        try
        {
            return VersionInfo.forClass(Rule.class).version();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }


    public @Nullable String engineVersionOrNull()
    {
        return engineVersion;
    }

    // ---- admission ------------------------------------------------------------------------

    /**
     * One admitted run of one runner; {@link #close() closing} it lets the runner run again.
     * Obtained from {@link #admit}, closed by whoever admitted (try-with-resources).
     */
    public final class Ticket implements AutoCloseable
    {

        private final String runner;

        private final boolean anonymous;

        private final AtomicBoolean closed = new AtomicBoolean();

        Ticket(String aRunner, boolean aAnonymous)
        {
            runner = aRunner;
            anonymous = aAnonymous;
        }


        /** Whether the run uses the anonymous pool and timeout. */
        public boolean anonymous()
        {
            return anonymous;
        }


        @Override
        public void close()
        {
            if (closed.compareAndSet(false, true))
            {
                active.remove(runner);
            }
        }
    }

    /** The runner key of a signed-in user: every session of the user is one runner. */
    public static String userRunner(long aUserId)
    {
        return "user:" + aUserId;
    }


    /** The runner key of an anonymous client (its address key). */
    public static String clientRunner(String aClientKey)
    {
        return "client:" + aClientKey;
    }


    /**
     * Admits a run: one at a time per runner (§5.7, security review M1 — a user cannot multiply
     * workers through tabs or logins), and the anonymous rate limit (§5.8.5).
     *
     * @param aRunner
     *            {@link #userRunner} or {@link #clientRunner}
     * @param aAnonymous
     *            an anonymous client: rate-limited, the anonymous pool
     * @throws EditorException
     *             {@code 409 RUN_IN_PROGRESS}, {@code 429 RATE_LIMITED}
     */
    public Ticket admit(String aRunner, boolean aAnonymous)
    {
        if (!active.add(aRunner))
        {
            throw new EditorException(HttpStatus.CONFLICT, "RUN_IN_PROGRESS",
                    "a run of yours is still in progress: one run at a time — wait for its "
                            + "result");
        }
        if (aAnonymous && !anonymousLimit.tryAcquire(aRunner))
        {
            active.remove(aRunner);
            throw new EditorException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "anonymous runs are limited to " + config.getAnonymousRunsPerMinute()
                            + " per minute per client");
        }
        return new Ticket(aRunner, aAnonymous);
    }


    /**
     * A scenario path is a {@code .cdt} under the suites tree (on top of the allow-list check the
     * caller made); checked before anything is admitted or exported.
     */
    public static void requireScenarioPath(String aScenarioPath)
    {
        if (!aScenarioPath.startsWith(CorpusLayout.SCENARIOS + "/")
                || !aScenarioPath.endsWith(CorpusLayout.CDT))
        {
            throw new EditorException(HttpStatus.BAD_REQUEST, "BAD_PATH",
                    "a scenario is a .cdt file under " + CorpusLayout.SCENARIOS);
        }
    }


    /**
     * Run one rule buffer against one scenario buffer: {@link #admit}, {@link #prepare},
     * {@link #execute} in one call — for a caller that holds no lock on the source (tests, an
     * immutable snapshot).
     *
     * @param aAnonymousKey
     *            the client key of an anonymous request, else {@code null} (no per-runner guard)
     */
    public RunReport run(CorpusSource aSource, String aCheckPath, String aCheckText,
            @Nullable String aDocsText, String aScenarioPath, String aCdtText,
            @Nullable String aAnonymousKey)
        throws IOException
    {
        requireScenarioPath(aScenarioPath);
        try (Ticket ticket = aAnonymousKey == null ? admit("inline:" + UUID.randomUUID(), false)
                : admit(clientRunner(aAnonymousKey), true))
        {
            return execute(
                    prepare(aSource, aCheckPath, aCheckText, aDocsText, aScenarioPath, aCdtText),
                    ticket);
        }
    }

    // ---- prepare (under the caller's lock) --------------------------------------------------

    /**
     * An exported run: the job and the scratch tree it lives in, deleted by {@link #execute}.
     *
     * @param job
     *            what the worker reads
     * @param scratch
     *            {@code .runs/<uuid>}
     */
    public record PreparedRun(RunJob job, Path scratch)
    {
    }

    /**
     * Export the suite directory and the buffers into a fresh scratch tree — the only step that
     * reads the source, so the only one that needs the caller's workspace lock. On failure nothing
     * is left behind (REST review #10).
     *
     * @param aSource
     *            the view (the suite directory's sidecars and the dictionaries come from it)
     * @param aCheckPath
     *            the rule's check path (names the docs sidecar)
     * @param aCheckText
     *            the rule buffer
     * @param aDocsText
     *            the docs buffer, or {@code null} to read the sidecar from the view
     * @param aScenarioPath
     *            the scenario's repository path (checked against the allow-list by the caller)
     * @param aCdtText
     *            the scenario buffer
     */
    public PreparedRun prepare(CorpusSource aSource, String aCheckPath, String aCheckText,
            @Nullable String aDocsText, String aScenarioPath, String aCdtText)
        throws IOException
    {
        requireScenarioPath(aScenarioPath);
        String docs = aDocsText;
        if (docs == null)
        {
            String docsPath = docsPathOf(aCheckPath);
            docs = docsPath == null ? null : CorpusText.read(aSource, docsPath).orElse(null);
        }
        JsonNode check = RuleAnalyser.readCheck(aCheckText);
        List<RunJob.StandardRefJson> refs = new ArrayList<>();
        if (check != null)
        {
            for (StandardRef r : standards.forCheck(aSource, check))
            {
                refs.add(new RunJob.StandardRefJson(r.id(), r.role().wireName()));
            }
        }
        Path scratch = Files.createDirectories(runsRoot.resolve(UUID.randomUUID().toString()));
        try
        {
            return new PreparedRun(
                    export(aSource, scratch, aCheckText, docs, refs, aScenarioPath, aCdtText),
                    scratch);
        }
        catch (IOException | RuntimeException e)
        {
            deleteTree(scratch);
            throw e;
        }
    }


    private RunJob export(CorpusSource aSource, Path aScratch, String aCheckText,
            @Nullable String aDocs, List<RunJob.StandardRefJson> aRefs, String aScenarioPath,
            String aCdtText)
        throws IOException
    {
        String suiteDir = aScenarioPath.substring(0, aScenarioPath.lastIndexOf('/'));
        Path suite = Files.createDirectories(aScratch.resolve(suiteDir));
        for (CorpusSource.TreeEntry e : aSource.list(suiteDir))
        {
            if (e.directory())
            {
                continue;
            }
            String path = suiteDir + "/" + e.name();
            if (path.equals(aScenarioPath) || e.name().endsWith(CorpusLayout.CDT))
            {
                continue; // the sidecars travel; the other scenarios are not this run's
            }
            Optional<byte[]> bytes = aSource.read(path);
            if (bytes.isPresent())
            {
                Files.write(suite.resolve(e.name()), bytes.get());
            }
        }
        Path scenarioFile = aScratch.resolve(aScenarioPath);
        Files.writeString(scenarioFile, aCdtText, StandardCharsets.UTF_8);
        String dictionaries = null;
        if (aCdtText.contains("#dictionaries"))
        {
            Path dir = aScratch.resolve(CorpusText.DICTIONARIES_DIR);
            int copied = 0;
            for (CorpusSource.TreeEntry e : aSource.list(CorpusText.DICTIONARIES_DIR))
            {
                Optional<byte[]> bytes = e.directory() ? Optional.empty()
                        : aSource.read(CorpusText.DICTIONARIES_DIR + "/" + e.name());
                if (bytes.isPresent())
                {
                    Files.createDirectories(dir);
                    Files.write(dir.resolve(e.name()), bytes.get());
                    copied++;
                }
            }
            dictionaries = copied == 0 ? null : dir.toString();
        }
        return new RunJob(aCheckText, aDocs, aRefs, aCdtText, scenarioFile.toString(),
                aScratch.resolve(CorpusLayout.SCENARIOS).toString(), dictionaries,
                store.path().map(Path::toString).orElse(null), engineVersion);
    }


    static @Nullable String docsPathOf(String aCheckPath)
    {
        return RuleAnalyser.docsPathOf(aCheckPath);
    }

    // ---- execute (no lock) --------------------------------------------------------------------


    /**
     * A worker slot of the ticket's pool, the worker, then the scratch tree deleted — the caller
     * holds no workspace lock here.
     *
     * @throws EditorException
     *             {@code 503 RUN_BUSY} when the pool and its queue are full
     */
    public RunReport execute(PreparedRun aRun, Ticket aTicket) throws IOException
    {
        Pool p = aTicket.anonymous() ? anonymousPool : pool;
        try
        {
            return p.withSlot(() -> spawn(aRun.job(), p.timeout));
        }
        finally
        {
            deleteTree(aRun.scratch());
        }
    }

    /** A unit of work in a worker slot. */
    @FunctionalInterface
    interface Work
    {

        RunReport call() throws IOException;
    }


    /**
     * A worker pool: a fair semaphore of {@code maxConcurrent} slots and a bounded queue in front
     * of it (REST review #10: the queue is entered by compare-and-set, never check-then-increment,
     * and an immediate acquire honours the fairness instead of barging past the queue).
     */
    static final class Pool
    {

        private final String name;

        private final Semaphore running;

        private final AtomicInteger queued = new AtomicInteger();

        private final int maxConcurrent;

        private final int maxQueued;

        private final Duration queueWait;

        final Duration timeout;

        Pool(String aName, int aMaxConcurrent, int aMaxQueued, Duration aQueueWait,
                Duration aTimeout)
        {
            name = aName;
            maxConcurrent = Math.max(1, aMaxConcurrent);
            maxQueued = Math.max(0, aMaxQueued);
            running = new Semaphore(maxConcurrent, true);
            queueWait = aQueueWait;
            timeout = aTimeout;
        }


        RunReport withSlot(Work aWork) throws IOException
        {
            if (!acquire())
            {
                throw busy();
            }
            try
            {
                return aWork.call();
            }
            finally
            {
                running.release();
            }
        }


        private boolean acquire()
        {
            try
            {
                // tryAcquire(0, …) honours the fair ordering; tryAcquire() would barge
                if (running.tryAcquire(0, TimeUnit.MILLISECONDS))
                {
                    return true;
                }
                if (!enterQueue())
                {
                    return false;
                }
                try
                {
                    return running.tryAcquire(queueWait.toMillis(), TimeUnit.MILLISECONDS);
                }
                finally
                {
                    queued.decrementAndGet();
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }


        /** A place in the queue, or false when it is full — never more than {@code maxQueued}. */
        boolean enterQueue()
        {
            while (true)
            {
                int q = queued.get();
                if (q >= maxQueued)
                {
                    return false;
                }
                if (queued.compareAndSet(q, q + 1))
                {
                    return true;
                }
            }
        }


        /** Leaves a place taken with {@link #enterQueue()} (tests). */
        void leaveQueue()
        {
            queued.decrementAndGet();
        }


        int queued()
        {
            return queued.get();
        }


        private EditorException busy()
        {
            return new EditorException(HttpStatus.SERVICE_UNAVAILABLE, "RUN_BUSY",
                    "every " + name + " worker is busy and the queue is full (" + maxConcurrent
                            + " running, " + maxQueued + " queued) — try again shortly");
        }
    }

    /**
     * Start a worker for the job, feed it from its own thread, wait up to the timeout — counted
     * from the start, not from the end of the feeding (security review L5) — and kill it past that.
     */
    RunReport spawn(RunJob aJob, Duration aTimeout) throws IOException
    {
        long t0 = System.nanoTime();
        byte[] job = Json.writeBytes(aJob);
        List<String> command = new ArrayList<>();
        command.add(javaCommand());
        command.add("-Xmx" + config.getMaxHeap());
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-XX:TieredStopAtLevel=1");
        command.add("-Djava.awt.headless=true");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Duser.home=" + System.getProperty("user.home"));
        command.add("-D" + RunWorker.WATCHDOG_PROPERTY + "="
                + aTimeout.plus(WATCHDOG_MARGIN).toMillis());
        command.add("-cp");
        command.add(classpath());
        command.add(RunWorker.class.getName());
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(Path.of(aJob.sidecarRoot()).toFile());
        Map<String, String> env = pb.environment();
        Map<String, String> kept = workerEnvironment(env);
        env.clear();
        env.putAll(kept);
        Process process = pb.start();
        Bounded stderr = new Bounded(config.getWorkerLogBytes());
        Thread errPump = pump(process.getErrorStream(), stderr);
        Collector stdout = new Collector(config.getMaxReportBytes(), process::destroyForcibly);
        Thread outPump = pump(process.getInputStream(), stdout);
        Thread feeder = feed(process, job);
        boolean finished;
        try
        {
            finished = process.waitFor(aTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished)
        {
            process.destroyForcibly();
            try
            {
                process.waitFor(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            join(feeder);
            join(outPump);
            join(errPump);
            LOG.warn("run worker killed after {} (pid {})", aTimeout, process.pid());
            return RunReport.stopped("ABORTED", "TIMEOUT", "the run exceeded "
                    + aTimeout.toSeconds()
                    + " s and was killed — a catastrophic expression or an exhausting join?",
                    List.of(), millis(t0), "child");
        }
        join(feeder);
        join(outPump);
        join(errPump);
        if (stdout.overflowed())
        {
            LOG.warn("run worker answered more than {} bytes and was killed (pid {})",
                    config.getMaxReportBytes(), process.pid());
            return RunReport.stopped("ABORTED", TOO_LARGE,
                    "the run's report exceeded " + config.getMaxReportBytes() / (1024 * 1024)
                            + " MiB and the worker was killed — too many findings for the "
                            + "editor; narrow the scenario",
                    List.of(), millis(t0), "child");
        }
        int exit = process.exitValue();
        if (exit != 0 || stdout.size() == 0)
        {
            LOG.warn("run worker exit {}: {}", exit, stderr.text());
            return RunReport.stopped("ABORTED", "WORKER_FAILED",
                    "the run worker ended with exit code " + exit
                            + (exit == 3
                                    ? " (out of memory: the heap cap " + config.getMaxHeap()
                                            + " was exhausted)"
                                    : "")
                            + " — the server log has its output",
                    List.of(), millis(t0), "child");
        }
        try (InputStream in = stdout.stream())
        {
            return Json.read(in, RunReport.class).withTiming(millis(t0), "child");
        }
        catch (RuntimeException e)
        {
            LOG.warn("run worker answered no report: {}", stderr.text(), e);
            return RunReport.stopped("ABORTED", "WORKER_FAILED",
                    "the run worker answered no report — the server log has its output", List.of(),
                    millis(t0), "child");
        }
    }


    /** The parent's environment reduced to {@link #WORKER_ENVIRONMENT} (case-insensitive). */
    static Map<String, String> workerEnvironment(Map<String, String> aParent)
    {
        Map<String, String> out = new LinkedHashMap<>();
        aParent.forEach((k, v) ->
        {
            if (WORKER_ENVIRONMENT.contains(k.toUpperCase(Locale.ROOT)))
            {
                out.put(k, v);
            }
        });
        return out;
    }


    /** Writes the job to the worker's stdin on a thread of its own, then closes it. */
    private static Thread feed(Process aProcess, byte[] aJob)
    {
        Thread t = new Thread(() ->
        {
            try (OutputStream in = aProcess.getOutputStream())
            {
                in.write(aJob);
            }
            catch (IOException e)
            {
                // the worker died or was killed before reading: its exit code tells
                LOG.debug("worker stdin closed early", e);
            }
        }, "run-worker-stdin");
        t.setDaemon(true);
        t.start();
        return t;
    }


    String javaCommand()
    {
        String configured = config.getJavaCommand();
        if (configured != null && !configured.isBlank())
        {
            return configured;
        }
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }


    /**
     * The worker's class path: configured, else under the bootstrap launcher the bundle's
     * {@code lib/*} ({@code bootstrap.dir} is the launcher's own property), else this JVM's.
     */
    String classpath()
    {
        String configured = config.getClasspath();
        if (configured != null && !configured.isBlank())
        {
            return configured;
        }
        String bootstrapDir = System.getProperty("bootstrap.dir");
        if (bootstrapDir != null && !bootstrapDir.isBlank())
        {
            return Path.of(bootstrapDir, "lib", "*").toString();
        }
        return System.getProperty("java.class.path");
    }


    private static Thread pump(InputStream aIn, Sink aSink)
    {
        Thread t = new Thread(() ->
        {
            try (InputStream in = aIn)
            {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0)
                {
                    aSink.accept(buf, n);
                }
            }
            catch (IOException e)
            {
                LOG.debug("worker stream closed", e);
            }
        }, "run-worker-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }


    private static void join(Thread aThread)
    {
        try
        {
            aThread.join(5_000);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    interface Sink
    {

        void accept(byte[] aBuffer, int aLength);
    }


    /**
     * Collects the worker's report up to a cap (security review M5): past it, the bytes are
     * dropped, the overflow is flagged and the worker is killed at once (it would only keep the CPU
     * busy). The report is parsed straight from the buffer — no String copy.
     */
    static final class Collector implements Sink
    {

        private final int limit;

        private final Runnable onOverflow;

        private byte[] bytes = new byte[8192];

        private int count;

        private boolean overflow;

        Collector(int aLimit, Runnable aOnOverflow)
        {
            limit = Math.max(1024, aLimit);
            onOverflow = aOnOverflow;
        }


        @Override
        public void accept(byte[] aBuffer, int aLength)
        {
            boolean kill = false;
            synchronized (this)
            {
                if (overflow)
                {
                    return;
                }
                if ((long) count + aLength > limit)
                {
                    overflow = true;
                    bytes = new byte[0];
                    count = 0;
                    kill = true;
                }
                else
                {
                    if (count + aLength > bytes.length)
                    {
                        bytes = Arrays.copyOf(bytes, (int) Math.min(limit,
                                Math.max(2L * bytes.length, count + aLength)));
                    }
                    System.arraycopy(aBuffer, 0, bytes, count, aLength);
                    count += aLength;
                }
            }
            if (kill)
            {
                onOverflow.run();
            }
        }


        synchronized boolean overflowed()
        {
            return overflow;
        }


        synchronized int size()
        {
            return count;
        }


        synchronized InputStream stream()
        {
            return new ByteArrayInputStream(bytes, 0, count);
        }
    }


    /** Keeps the last {@code limit} bytes. */
    static final class Bounded implements Sink
    {

        private final int limit;

        private byte[] tail = new byte[0];

        Bounded(int aLimit)
        {
            limit = Math.max(256, aLimit);
        }


        @Override
        public synchronized void accept(byte[] aBuffer, int aLength)
        {
            byte[] joined = new byte[tail.length + aLength];
            System.arraycopy(tail, 0, joined, 0, tail.length);
            System.arraycopy(aBuffer, 0, joined, tail.length, aLength);
            if (joined.length > limit)
            {
                byte[] cut = new byte[limit];
                System.arraycopy(joined, joined.length - limit, cut, 0, limit);
                joined = cut;
            }
            tail = joined;
        }


        synchronized String text()
        {
            return new String(tail, StandardCharsets.UTF_8);
        }
    }

    static void deleteTree(Path aRoot)
    {
        if (!Files.exists(aRoot))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(aRoot))
        {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(p);
            }
        }
        catch (IOException e)
        {
            LOG.warn("run scratch {} not fully deleted: {}", aRoot.getFileName(), e.getMessage());
        }
    }


    private static long millis(long aStartNanos)
    {
        return (System.nanoTime() - aStartNanos) / 1_000_000;
    }
}
