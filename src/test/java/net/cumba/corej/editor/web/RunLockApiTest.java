package net.cumba.corej.editor.web;

import static net.cumba.corej.editor.web.ApiTestSupport.body;
import static net.cumba.corej.editor.web.ApiTestSupport.csrf;
import static net.cumba.corej.editor.web.ApiTestSupport.json;
import static net.cumba.corej.editor.web.ApiTestSupport.login;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import net.cumba.corej.editor.HomeStaysCleanExtension;
import net.cumba.corej.editor.engine.Json;
import net.cumba.corej.editor.git.Workspace;
import net.cumba.corej.editor.git.WorkspaceService;
import net.cumba.corej.editor.testing.FakeForge;
import net.cumba.corej.editor.testing.RemoteRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Security review M1 / REST review #1 through the API: a run exports under the workspace lock and
 * runs OUTSIDE it, so the user's other requests are served while a worker runs; a second run of the
 * same user is refused ({@code 409 RUN_IN_PROGRESS}) instead of parking a thread; a lock held too
 * long answers {@code 503 WORKSPACE_BUSY} instead of parking every request behind it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiTestSupport.Fakes.class)
@ExtendWith(HomeStaysCleanExtension.class)
class RunLockApiTest
{

    /** {@code ^(.*a){12}$} over a long run of a's: exponential backtracking (RunServiceTest). */
    static final String CATASTROPHIC = RemoteRepo.CHECK_B_TEXT
            .replace("AEDECOD != \"\" and AETERM == \"\"", "AETERM =~ /^(.*a){12}$/");

    static final String SLOW_SCENARIO = RemoteRepo.SCENARIO_B_INVALID_TEXT.replace(
            "STUDY1 | 01-001 | 1 | \"\" | HEADACHE",
            "STUDY1 | 01-001 | 1 | " + "a".repeat(38) + "! | X");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry aRegistry)
    {
        aRegistry.add("editor.repository.url", () -> ApiTestSupport.remote().url());
        aRegistry.add("editor.workspace-root",
                () -> ApiTestSupport.root().resolve("workspaces-runlock").toString());
        aRegistry.add("editor.forge.api-url", () -> "http://127.0.0.1:1/api/v1");
        aRegistry.add("editor.anonymous.enabled", () -> "false");
        aRegistry.add("editor.run.timeout", () -> "PT8S");
        aRegistry.add("editor.run.max-heap", () -> "256m");
        aRegistry.add("editor.limits.workspace-lock-wait", () -> "PT1S");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private WorkspaceService workspaces;

    static String runBody(String aCheck, String aCdt)
    {
        return "{\"path\":\"" + RemoteRepo.CHECK_B + "\",\"content\":" + Json.write(aCheck)
                + ",\"scenarioPath\":\"" + RemoteRepo.SCENARIO_B_INVALID + "\",\"scenarioContent\":"
                + Json.write(aCdt) + "}";
    }


    @Test
    void aRunningWorkerHoldsNoLockAndASecondRunIsRefused() throws Exception
    {
        MockHttpSession s = login(mvc, "mw", FakeForge.WRITE_TOKEN);
        String token = csrf(mvc, s);
        AtomicReference<MvcResult> slow = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread run = new Thread(() ->
        {
            try
            {
                slow.set(mvc.perform(
                        json(post("/api/run"), s, token, runBody(CATASTROPHIC, SLOW_SCENARIO)))
                        .andReturn());
            }
            catch (Exception e)
            {
                failed.set(e);
            }
        });
        run.start();
        Thread.sleep(2500); // past the export: the worker is running now

        long t0 = System.nanoTime();
        MvcResult status = mvc.perform(get("/api/status").session(s)).andReturn();
        long millis = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(200, status.getResponse().getStatus(),
                status.getResponse().getContentAsString());
        assertTrue(millis < 3000, "the status answered while the worker ran (" + millis + " ms)");

        MvcResult second = mvc
                .perform(json(post("/api/run"), s, token,
                        runBody(RemoteRepo.CHECK_B_TEXT, RemoteRepo.SCENARIO_B_INVALID_TEXT)))
                .andReturn();
        assertEquals(409, second.getResponse().getStatus(),
                second.getResponse().getContentAsString());
        assertEquals("RUN_IN_PROGRESS", body(second).path("code").asString());

        run.join(120_000);
        assertEquals(null, failed.get());
        assertNotNull(slow.get());
        assertEquals("TIMEOUT", body(slow.get()).path("report").path("code").asString(),
                slow.get().getResponse().getContentAsString());
        // free again once it ended
        MvcResult again = mvc
                .perform(json(post("/api/run"), s, token,
                        runBody(RemoteRepo.CHECK_B_TEXT, RemoteRepo.SCENARIO_B_INVALID_TEXT)))
                .andReturn();
        assertEquals(200, again.getResponse().getStatus());
        assertEquals(RemoteRepo.SCENARIO_B_INVALID, body(again).path("scenarioPath").asString(),
                "the run names its scenario");
        assertEquals(RemoteRepo.CHECK_B, body(again).path("path").asString());
    }


    @SuppressWarnings("PMD.CloseResource") // owned by the WorkspaceService
    @Test
    void aWorkspaceLockedTooLongAnswersBusy() throws Exception
    {
        MockHttpSession s = login(mvc, "mw", FakeForge.WRITE_TOKEN);
        Workspace ws = workspaces.peek(FakeForge.WRITER.id());
        assertNotNull(ws);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() ->
        {
            ws.lock().lock();
            try
            {
                held.countDown();
                release.await();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                ws.lock().unlock();
            }
        });
        holder.start();
        held.await();
        try
        {
            MvcResult busy = mvc.perform(get("/api/status").session(s)).andReturn();
            assertEquals(503, busy.getResponse().getStatus());
            assertEquals(Access.WORKSPACE_BUSY, body(busy).path("code").asString());
        }
        finally
        {
            release.countDown();
            holder.join(10_000);
        }
        assertEquals(200,
                mvc.perform(get("/api/status").session(s)).andReturn().getResponse().getStatus(),
                "served again once the lock is free");
    }
}
