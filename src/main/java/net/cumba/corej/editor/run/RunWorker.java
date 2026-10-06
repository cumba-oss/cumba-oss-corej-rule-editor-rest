package net.cumba.corej.editor.run;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import net.cumba.corej.editor.engine.Json;

/**
 * The run worker's entry point: one {@link RunJob} as JSON on stdin, one {@link RunReport} as JSON
 * on stdout, then exit — a fresh JVM per run (PLAN-rule-editor §5.8.5, review H5), so a
 * catastrophic regex or an exhausting join is killed with the process and never reaches the API
 * JVM's heap. The parent passes {@code -Xmx} and enforces the wall-clock timeout.
 *
 * <p>
 * Exit codes: 0 with a report; 2 when the job could not be read or the executor threw (the message
 * on stderr, which the parent keeps bounded). Nothing but the report goes to stdout: the engine
 * logs through {@code System.Logger} → stderr.
 * </p>
 */
public final class RunWorker
{

    static final int EXIT_FAILED = 2;

    private RunWorker()
    {
    }


    @SuppressWarnings("PMD.CloseResource") // System.out is the JVM's, never closed here
    public static void main(String[] aArgs)
    {
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
}
