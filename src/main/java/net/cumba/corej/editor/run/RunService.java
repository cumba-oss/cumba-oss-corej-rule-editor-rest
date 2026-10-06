package net.cumba.corej.editor.run;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
 * way, and the workspace lock is held only while exporting.
 *
 * <p>
 * Limits: {@code editor.run.max-concurrent} workers at once, {@code max-queued} waiting (beyond
 * that {@code 503 RUN_BUSY}), {@code timeout} wall-clock after which the process is killed
 * ({@code ABORTED TIMEOUT}), {@code max-heap} as the worker's {@code -Xmx}; anonymous requests are
 * rate-limited per client address ({@code 429 RATE_LIMITED}).
 * </p>
 */
@Component
public final class RunService
{

    private static final Logger LOG = LoggerFactory.getLogger(RunService.class);

    static final String RUNS_DIR = ".runs";

    private final EditorProperties.Run config;

    private final EngineStore store;

    private final PackageStandards standards;

    private final Path runsRoot;

    private final Semaphore running;

    private final AtomicInteger queued = new AtomicInteger();

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
        running = new Semaphore(Math.max(1, config.getMaxConcurrent()), true);
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


    /**
     * Run one rule buffer against one scenario buffer.
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
     * @param aAnonymousKey
     *            the client address for an anonymous request, else {@code null}
     * @return the report
     */
    public RunReport run(CorpusSource aSource, String aCheckPath, String aCheckText,
            @Nullable String aDocsText, String aScenarioPath, String aCdtText,
            @Nullable String aAnonymousKey)
        throws IOException
    {
        if (aAnonymousKey != null && !anonymousLimit.tryAcquire(aAnonymousKey))
        {
            throw new EditorException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "anonymous runs are limited to " + config.getAnonymousRunsPerMinute()
                            + " per minute per client");
        }
        if (!aScenarioPath.startsWith(CorpusLayout.SCENARIOS + "/")
                || !aScenarioPath.endsWith(CorpusLayout.CDT))
        {
            throw new EditorException(HttpStatus.BAD_REQUEST, "BAD_PATH",
                    "a scenario is a .cdt file under " + CorpusLayout.SCENARIOS);
        }
        RunJob job = prepare(aSource, aCheckPath, aCheckText, aDocsText, aScenarioPath, aCdtText);
        Path scratch = Path.of(job.sidecarRoot());
        while (scratch.getParent() != null && !runsRoot.equals(scratch.getParent()))
        {
            scratch = scratch.getParent();
        }
        try
        {
            return withSlot(() -> spawn(job));
        }
        finally
        {
            deleteTree(scratch);
        }
    }


    /** Export the suite directory and the buffers into a fresh scratch tree. */
    RunJob prepare(CorpusSource aSource, String aCheckPath, String aCheckText,
            @Nullable String aDocsText, String aScenarioPath, String aCdtText)
        throws IOException
    {
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
        String suiteDir = aScenarioPath.substring(0, aScenarioPath.lastIndexOf('/'));
        Path suite = Files.createDirectories(scratch.resolve(suiteDir));
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
        Path scenarioFile = scratch.resolve(aScenarioPath);
        Files.writeString(scenarioFile, aCdtText, StandardCharsets.UTF_8);
        String dictionaries = null;
        if (aCdtText.contains("#dictionaries"))
        {
            Path dir = scratch.resolve(CorpusText.DICTIONARIES_DIR);
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
        return new RunJob(aCheckText, docs, refs, aCdtText, scenarioFile.toString(),
                scratch.resolve(CorpusLayout.SCENARIOS).toString(), dictionaries,
                store.path().map(Path::toString).orElse(null), engineVersion);
    }


    static @Nullable String docsPathOf(String aCheckPath)
    {
        return RuleAnalyser.docsPathOf(aCheckPath);
    }

    private interface Work
    {

        RunReport call() throws IOException;
    }

    private RunReport withSlot(Work aWork) throws IOException
    {
        boolean acquired = running.tryAcquire();
        if (!acquired)
        {
            // every worker busy: wait in the queue when it has room, else refuse at once
            if (queued.get() >= config.getMaxQueued())
            {
                throw busy();
            }
            queued.incrementAndGet();
            try
            {
                acquired = running.tryAcquire(config.getQueueWait().toMillis(),
                        TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                queued.decrementAndGet();
            }
            if (!acquired)
            {
                throw busy();
            }
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


    private EditorException busy()
    {
        return new EditorException(HttpStatus.SERVICE_UNAVAILABLE, "RUN_BUSY",
                "every run worker is busy and the queue is full (" + config.getMaxConcurrent()
                        + " running, " + config.getMaxQueued() + " queued) — try again shortly");
    }


    /** Start a worker for the job, feed it, wait up to the timeout, kill it past that. */
    RunReport spawn(RunJob aJob) throws IOException
    {
        long t0 = System.nanoTime();
        List<String> command = new ArrayList<>();
        command.add(javaCommand());
        command.add("-Xmx" + config.getMaxHeap());
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-XX:TieredStopAtLevel=1");
        command.add("-Djava.awt.headless=true");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Duser.home=" + System.getProperty("user.home"));
        command.add("-cp");
        command.add(classpath());
        command.add(RunWorker.class.getName());
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(Path.of(aJob.sidecarRoot()).toFile());
        Process process = pb.start();
        Bounded stderr = new Bounded(config.getWorkerLogBytes());
        Thread errPump = pump(process.getErrorStream(), stderr);
        Collector stdout = new Collector();
        Thread outPump = pump(process.getInputStream(), stdout);
        try (OutputStream in = process.getOutputStream())
        {
            in.write(Json.write(aJob).getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            // the worker died before reading: reported by its exit code below
            LOG.debug("worker stdin closed early", e);
        }
        boolean finished;
        try
        {
            finished = process.waitFor(config.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
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
            join(outPump);
            join(errPump);
            LOG.warn("run worker killed after {} (pid {})", config.getTimeout(), process.pid());
            return RunReport.stopped("ABORTED", "TIMEOUT", "the run exceeded "
                    + config.getTimeout().toSeconds()
                    + " s and was killed — a catastrophic expression or an exhausting join?",
                    List.of(), millis(t0), "child");
        }
        join(outPump);
        join(errPump);
        int exit = process.exitValue();
        String out = stdout.text();
        if (exit != 0 || out.isBlank())
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
        try
        {
            return Json.read(out, RunReport.class).withTiming(millis(t0), "child");
        }
        catch (RuntimeException e)
        {
            LOG.warn("run worker answered no report: {}", stderr.text(), e);
            return RunReport.stopped("ABORTED", "WORKER_FAILED",
                    "the run worker answered no report — the server log has its output", List.of(),
                    millis(t0), "child");
        }
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


    /** Collects everything. */
    private static final class Collector implements Sink
    {

        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

        @Override
        public synchronized void accept(byte[] aBuffer, int aLength)
        {
            bytes.write(aBuffer, 0, aLength);
        }


        synchronized String text()
        {
            return bytes.toString(StandardCharsets.UTF_8);
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
            LOG.warn("run scratch {} not fully deleted: {}", aRoot, e.getMessage());
        }
    }


    private static long millis(long aStartNanos)
    {
        return (System.nanoTime() - aStartNanos) / 1_000_000;
    }
}
