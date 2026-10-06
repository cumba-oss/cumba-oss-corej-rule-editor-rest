package net.cumba.corej.editor.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.corpus.CorpusLayout;
import net.cumba.corej.editor.engine.CorpusText;
import net.cumba.corej.editor.engine.EngineStore;
import net.cumba.corej.editor.engine.PackageStandards;
import net.cumba.corej.editor.error.EditorException;
import net.cumba.corej.editor.git.WorkspaceService;
import net.cumba.corej.editor.testing.MapSource;
import net.cumba.corej.editor.testing.RemoteRepo;
import net.cumba.corej.editor.testing.TestStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run workers as child JVMs (§5.8.5, review H5): a real worker judges the fixture scenario; a
 * catastrophic regex is killed at the wall-clock timeout; the queue refuses beyond its limit;
 * anonymous runs are rate-limited; the scratch export is confined and cleaned up.
 */
class RunServiceTest
{

    /**
     * {@code ^(.*a){12}$} against a long run of a's that cannot match: exponential backtracking
     * (measured on JDK 25, 2026-10-06, through the executor: 20 a's 0.1 s, 26 a's 0.6 s, ×1.5 per a
     * — the classic {@code (a+)+} is optimised away by this JDK's regex engine and is NOT a
     * fixture).
     */
    static final String CATASTROPHIC = RemoteRepo.CHECK_B_TEXT
            .replace("AEDECOD != \"\" and AETERM == \"\"", "AETERM =~ /^(.*a){12}$/");

    static final String LONG_A = "a".repeat(38) + "!";

    static final String SLOW_SCENARIO = RemoteRepo.SCENARIO_B_INVALID_TEXT.replace(
            "STUDY1 | 01-001 | 1 | \"\" | HEADACHE", "STUDY1 | 01-001 | 1 | " + LONG_A + " | X");

    private static EngineStore store;

    private static PackageStandards standards;

    private static Path root;

    @BeforeAll
    static void open() throws IOException
    {
        store = new EngineStore(TestStore.file());
        standards = new PackageStandards();
        root = Files.createTempDirectory(Path.of("").toAbsolutePath(), "run-service-");
    }


    @AfterAll
    static void close() throws IOException
    {
        store.close();
        standards.close();
    }


    static EditorProperties properties(Path aWorkspaceRoot, Duration aTimeout, int aConcurrent,
            int aQueued, int aAnonymousPerMinute)
    {
        EditorProperties p = new EditorProperties();
        p.getRepository().setUrl("https://forge.example.test/acme/rules.git");
        p.setWorkspaceRoot(aWorkspaceRoot.toString());
        p.getRun().setTimeout(aTimeout);
        p.getRun().setMaxConcurrent(aConcurrent);
        p.getRun().setMaxQueued(aQueued);
        p.getRun().setQueueWait(Duration.ofMillis(200));
        p.getRun().setAnonymousRunsPerMinute(aAnonymousPerMinute);
        p.getRun().setMaxHeap("256m");
        return p;
    }


    static RunService service(String aName, Duration aTimeout, int aConcurrent, int aQueued,
            int aAnonymousPerMinute)
        throws IOException
    {
        EditorProperties p = properties(root.resolve(aName), aTimeout, aConcurrent, aQueued,
                aAnonymousPerMinute);
        return new RunService(p, store, standards, new WorkspaceService(p));
    }


    static MapSource corpus()
    {
        return new MapSource(RemoteRepo.INITIAL_FILES);
    }


    static long entries(Path aDir) throws IOException
    {
        if (!Files.isDirectory(aDir))
        {
            return 0;
        }
        try (Stream<Path> s = Files.list(aDir))
        {
            return s.count();
        }
    }


    @Test
    void aWorkerJudgesTheScenarioAndTheScratchIsCleanedUp() throws IOException
    {
        RunService runs = service("ws-ok", Duration.ofMinutes(2), 2, 2, 0);
        RunReport r = runs.run(corpus(), RemoteRepo.CHECK_B, RemoteRepo.CHECK_B_TEXT, null,
                RemoteRepo.SCENARIO_B_INVALID, RemoteRepo.SCENARIO_B_INVALID_TEXT, null);
        assertEquals("PASS", r.outcome(), r.detail());
        assertEquals("OK", r.code());
        assertEquals("child", r.worker());
        assertEquals(1, r.findings().size());
        assertNotNull(r.reportJson());
        assertNotNull(r.expectations());
        assertTrue(r.durationMs() > 0);
        assertEquals(0, entries(root.resolve("ws-ok").resolve(RunService.RUNS_DIR)),
                "the scratch tree is deleted after the run");
        assertNotNull(runs.engineVersionOrNull(), "the engine's version is read from its jar");
    }


    @Test
    void aCatastrophicRegexIsKilledAtTheTimeout() throws IOException
    {
        RunService runs = service("ws-timeout", Duration.ofSeconds(4), 1, 1, 0);
        long t0 = System.nanoTime();
        RunReport r = runs.run(corpus(), RemoteRepo.CHECK_B, CATASTROPHIC, null,
                RemoteRepo.SCENARIO_B_INVALID, SLOW_SCENARIO, null);
        long seconds = (System.nanoTime() - t0) / 1_000_000_000L;
        assertEquals("ABORTED", r.outcome(), r.detail());
        assertEquals("TIMEOUT", r.code());
        assertEquals("child", r.worker());
        assertTrue(seconds >= 3 && seconds < 60, "killed at the timeout, took " + seconds + " s");
        assertEquals(0, entries(root.resolve("ws-timeout").resolve(RunService.RUNS_DIR)));
    }


    @Test
    void theQueueRefusesBeyondItsLimit() throws Exception
    {
        RunService runs = service("ws-busy", Duration.ofSeconds(6), 1, 0, 0);
        AtomicReference<RunReport> slow = new AtomicReference<>();
        Thread t = new Thread(() ->
        {
            try
            {
                slow.set(runs.run(corpus(), RemoteRepo.CHECK_B, CATASTROPHIC, null,
                        RemoteRepo.SCENARIO_B_INVALID, SLOW_SCENARIO, null));
            }
            catch (IOException e)
            {
                throw new IllegalStateException(e);
            }
        });
        t.start();
        Thread.sleep(1500);
        EditorException busy = assertThrows(EditorException.class,
                () -> runs.run(corpus(), RemoteRepo.CHECK_B, RemoteRepo.CHECK_B_TEXT, null,
                        RemoteRepo.SCENARIO_B_INVALID, RemoteRepo.SCENARIO_B_INVALID_TEXT, null));
        assertEquals("RUN_BUSY", busy.code());
        assertEquals(503, busy.status().value());
        t.join(60_000);
        assertNotNull(slow.get());
        assertEquals("TIMEOUT", slow.get().code());
    }


    @Test
    void anonymousRunsAreRateLimitedPerClient() throws IOException
    {
        RunService runs = service("ws-anon", Duration.ofMinutes(2), 1, 1, 1);
        RunReport first = runs.run(corpus(), RemoteRepo.CHECK_B, RemoteRepo.CHECK_B_TEXT, null,
                RemoteRepo.SCENARIO_B_VALID,
                RemoteRepo.INITIAL_FILES.get(RemoteRepo.SCENARIO_B_VALID), "client-a");
        assertEquals("PASS", first.outcome(), first.detail());
        EditorException limited = assertThrows(EditorException.class,
                () -> runs.run(corpus(), RemoteRepo.CHECK_B, RemoteRepo.CHECK_B_TEXT, null,
                        RemoteRepo.SCENARIO_B_VALID,
                        RemoteRepo.INITIAL_FILES.get(RemoteRepo.SCENARIO_B_VALID), "client-a"));
        assertEquals("RATE_LIMITED", limited.code());
        assertEquals(429, limited.status().value());
        // another client, and an authenticated session (null key), are not limited
        assertEquals("PASS",
                runs.run(corpus(), RemoteRepo.CHECK_B, RemoteRepo.CHECK_B_TEXT, null,
                        RemoteRepo.SCENARIO_B_VALID,
                        RemoteRepo.INITIAL_FILES.get(RemoteRepo.SCENARIO_B_VALID), "client-b")
                        .outcome());
        assertEquals("PASS",
                runs.run(corpus(), RemoteRepo.CHECK_B, RemoteRepo.CHECK_B_TEXT, null,
                        RemoteRepo.SCENARIO_B_VALID,
                        RemoteRepo.INITIAL_FILES.get(RemoteRepo.SCENARIO_B_VALID), null).outcome());
    }


    @Test
    void theExportHoldsTheSuiteSidecarsTheBufferAndTheDictionaries() throws IOException
    {
        RunService runs = service("ws-export", Duration.ofMinutes(2), 1, 1, 0);
        MapSource source = corpus()
                .put(CorpusLayout.scenarioDir("CDISC", RemoteRepo.RULE_B) + "/library.json",
                        "{\"sidecar\": true}")
                .put(CorpusText.DICTIONARIES_DIR + "/meddra.json", "{}");
        String withDictionaries = "#dictionaries dummy\n" + RemoteRepo.SCENARIO_B_INVALID_TEXT;
        RunJob job = runs.prepare(source, RemoteRepo.CHECK_B, RemoteRepo.CHECK_B_TEXT, null,
                RemoteRepo.SCENARIO_B_INVALID, withDictionaries);
        try
        {
            Path scenario = Path.of(job.scenarioFile());
            assertEquals(withDictionaries, Files.readString(scenario));
            assertTrue(Files.isRegularFile(scenario.resolveSibling("library.json")),
                    "the suite directory's other files travel with the scenario");
            assertFalse(Files.exists(scenario.resolveSibling(RemoteRepo.RULE_B + "-valid-AE.cdt")),
                    "the other scenarios do not");
            assertNotNull(job.dictionariesDir());
            assertTrue(Files.isRegularFile(Path.of(job.dictionariesDir()).resolve("meddra.json")));
            assertTrue(scenario.startsWith(Path.of(job.sidecarRoot())),
                    "the scenario sits inside the sidecar root");
            assertEquals(TestStore.file().toString(), job.storePath());
            assertEquals(List.of(new RunJob.StandardRefJson("sdtmig/3-4", "primary")),
                    job.packageStandards());
            assertEquals(null, job.docYaml(), "CG0270 has no docs sidecar");
        }
        finally
        {
            RunService.deleteTree(Path.of(job.sidecarRoot()).getParent().getParent().getParent()
                    .getParent().getParent().getParent().getParent());
        }
        RunJob plain = runs.prepare(source, RemoteRepo.CHECK_A,
                RemoteRepo.INITIAL_FILES.get(RemoteRepo.CHECK_A), null, RemoteRepo.SCENARIO_A,
                RemoteRepo.INITIAL_FILES.get(RemoteRepo.SCENARIO_A));
        assertEquals(null, plain.dictionariesDir(), "no #dictionaries: nothing exported");
        assertNotNull(plain.docYaml(), "AD0001's docs sidecar is read from the view");
    }


    @Test
    void aScenarioPathOutsideTheSuitesIsRefusedBeforeAnyExport() throws IOException
    {
        RunService runs = service("ws-path", Duration.ofMinutes(2), 1, 1, 0);
        EditorException e = assertThrows(EditorException.class,
                () -> runs.run(corpus(), RemoteRepo.CHECK_B, RemoteRepo.CHECK_B_TEXT, null,
                        RemoteRepo.CHECK_B, RemoteRepo.SCENARIO_B_INVALID_TEXT, null));
        assertEquals("BAD_PATH", e.code());
        assertEquals(0, entries(root.resolve("ws-path").resolve(RunService.RUNS_DIR)));
    }


    @Test
    void theWorkerClasspathIsConfiguredOrDerived(@TempDir Path aDir) throws IOException
    {
        EditorProperties p = properties(aDir, Duration.ofSeconds(1), 1, 1, 0);
        RunService runs = new RunService(p, store, standards, new WorkspaceService(p));
        assertEquals(System.getProperty("java.class.path"), runs.classpath(),
                "this JVM's class path when nothing else is known");
        assertTrue(runs.javaCommand().endsWith("java"));
        p.getRun().setClasspath("/opt/editor/lib/*");
        p.getRun().setJavaCommand("/opt/jdk/bin/java");
        assertEquals("/opt/editor/lib/*", runs.classpath());
        assertEquals("/opt/jdk/bin/java", runs.javaCommand());
        p.getRun().setClasspath(null);
        String before = System.getProperty("bootstrap.dir");
        System.setProperty("bootstrap.dir", "/opt/bundle");
        try
        {
            assertEquals(Path.of("/opt/bundle", "lib", "*").toString(), runs.classpath(),
                    "under the bootstrap launcher: the bundle's lib/*");
        }
        finally
        {
            if (before == null)
            {
                System.clearProperty("bootstrap.dir");
            }
            else
            {
                System.setProperty("bootstrap.dir", before);
            }
        }
    }


    @Test
    void aWorkerThatCannotStartIsReportedNotThrown(@TempDir Path aDir) throws IOException
    {
        EditorProperties p = properties(aDir, Duration.ofSeconds(30), 1, 1, 0);
        p.getRun().setClasspath(aDir.resolve("nothing").toString());
        RunService runs = new RunService(p, store, standards, new WorkspaceService(p));
        RunReport r = runs.run(corpus(), RemoteRepo.CHECK_B, RemoteRepo.CHECK_B_TEXT, null,
                RemoteRepo.SCENARIO_B_INVALID, RemoteRepo.SCENARIO_B_INVALID_TEXT, null);
        assertEquals("ABORTED", r.outcome());
        assertEquals("WORKER_FAILED", r.code());
        assertTrue(r.detail().contains("exit code"), r.detail());
    }


    @Test
    void theBoundedLogKeepsTheTail()
    {
        RunService.Bounded b = new RunService.Bounded(300);
        byte[] chunk = "0123456789".repeat(20).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        b.accept(chunk, chunk.length);
        b.accept(chunk, chunk.length);
        assertEquals(300, b.text().length());
        assertTrue(b.text().endsWith("0123456789"));
    }
}
