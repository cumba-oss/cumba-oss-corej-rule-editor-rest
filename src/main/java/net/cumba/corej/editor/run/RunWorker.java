package net.cumba.corej.editor.run;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import net.cumba.corej.editor.engine.Json;
import org.jspecify.annotations.Nullable;

/**
 * The run worker's entry point: one {@link RunJob} as JSON on stdin, one {@link RunReport} as JSON
 * on stdout, then exit — a fresh JVM per run (PLAN-rule-editor §5.8.5, review H5), so a
 * catastrophic regex or an exhausting join is killed with the process and never reaches the API
 * JVM's heap. The parent passes {@code -Xmx} and enforces the wall-clock timeout.
 *
 * <p>
 * A watchdog of its own (security review L5) halts the worker when the parent that started it is
 * gone — a killed or crashed API JVM must not leave workers behind — or when it outlives the
 * parent's timeout by a margin ({@link #WATCHDOG_PROPERTY}, in milliseconds), should the parent's
 * kill not have happened.
 * </p>
 *
 * <p>
 * Exit codes: 0 with a report; 2 when the job could not be read or the executor threw (the message
 * on stderr, which the parent keeps bounded); 3 out of memory
 * ({@code -XX:+ExitOnOutOfMemoryError}); 4 the parent is gone; 5 overdue. Nothing but the report
 * goes to stdout: the engine logs through {@code System.Logger} → stderr.
 * </p>
 */
public final class RunWorker
{

    static final int EXIT_FAILED = 2;

    static final int EXIT_ORPHANED = 4;

    static final int EXIT_OVERDUE = 5;

    /** The system property carrying the watchdog's limit in milliseconds (absent: no limit). */
    static final String WATCHDOG_PROPERTY = "cumba.editor.worker.watchdog-ms";

    private static final long WATCHDOG_PERIOD_MS = 500;

    private RunWorker()
    {
    }


    @SuppressWarnings("PMD.CloseResource") // System.out is the JVM's, never closed here
    public static void main(String[] aArgs)
    {
        startWatchdog(Long.getLong(WATCHDOG_PROPERTY, 0L));
        PrintStream out = System.out;
        // Anything the engine prints must not corrupt the report: route stdout to stderr for the
        // duration of the run and write the report through the original stream.
        System.setOut(System.err);
        try
        {
            String job = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            RunReport report = RunExecutor.execute(Json.read(job, RunJob.class));
            out.print(Json.write(report));
            out.flush();
            System.exit(0);
        }
        catch (IOException | RuntimeException e)
        {
            System.err.println("run worker failed: " + e);
            System.exit(EXIT_FAILED);
        }
    }


    /** Starts the daemon that halts this JVM when it is orphaned or overdue. */
    static void startWatchdog(long aLimitMillis)
    {
        ProcessHandle parent = ProcessHandle.current().parent().orElse(null);
        long t0 = System.nanoTime();
        Thread t = new Thread(() ->
        {
            while (true)
            {
                try
                {
                    Thread.sleep(WATCHDOG_PERIOD_MS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
                int code = verdict(parent, (System.nanoTime() - t0) / 1_000_000, aLimitMillis);
                if (code != 0)
                {
                    System.err.println("run worker halted by its watchdog: "
                            + (code == EXIT_ORPHANED ? "the server that started it is gone"
                                    : "it outlived its timeout"));
                    Runtime.getRuntime().halt(code);
                }
            }
        }, "run-worker-watchdog");
        t.setDaemon(true);
        t.start();
    }


    /**
     * The watchdog's decision.
     *
     * @param aParent
     *            the process that started this worker ({@code null}: unknown, never orphaned)
     * @param aElapsedMillis
     *            how long the worker runs
     * @param aLimitMillis
     *            the limit; 0 or less is none
     * @return 0 to carry on, else the exit code to halt with
     */
    static int verdict(@Nullable ProcessHandle aParent, long aElapsedMillis, long aLimitMillis)
    {
        if (aParent != null && !aParent.isAlive())
        {
            return EXIT_ORPHANED;
        }
        if (aLimitMillis > 0 && aElapsedMillis > aLimitMillis)
        {
            return EXIT_OVERDUE;
        }
        return 0;
    }
}
