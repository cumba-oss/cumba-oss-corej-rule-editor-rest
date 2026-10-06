package net.cumba.corej.editor.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The worker's own watchdog (security review L5): it halts a worker whose parent is gone, or that
 * outlived its limit, and leaves a healthy one alone.
 */
class RunWorkerTest
{

    @Test
    void theWatchdogHaltsAnOrphanedOrOverdueWorkerOnly() throws IOException, InterruptedException
    {
        ProcessHandle alive = ProcessHandle.current();
        assertEquals(0, RunWorker.verdict(alive, 1_000, 5_000), "alive, in time");
        assertEquals(0, RunWorker.verdict(alive, 1_000_000, 0), "no limit");
        assertEquals(0, RunWorker.verdict(null, 1_000, 5_000), "parent unknown: never orphaned");
        assertEquals(RunWorker.EXIT_OVERDUE, RunWorker.verdict(alive, 5_001, 5_000));
        Process finished = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
                        .redirectErrorStream(true).start();
        finished.getInputStream().readAllBytes();
        finished.waitFor(60, TimeUnit.SECONDS);
        ProcessHandle gone = finished.toHandle();
        assertFalse(gone.isAlive());
        assertEquals(RunWorker.EXIT_ORPHANED, RunWorker.verdict(gone, 1_000, 5_000),
                "the server that started it is gone");
    }
}
