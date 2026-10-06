package net.cumba.corej.editor.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.cumba.corej.editor.testing.RemoteRepo;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** §5.1.5: untouched AND nothing uncommitted or unpushed ⇒ deleted; anything else ⇒ kept. */
@SuppressWarnings("PMD.CloseResource") // every Workspace is owned by the service under test
class WorkspaceRetentionTest
{

    @TempDir
    Path tmp;

    private RemoteRepo remote;

    private WorkspaceService service;

    @BeforeEach
    void setUp() throws IOException, GitAPIException
    {
        remote = RemoteRepo.create(tmp.resolve("remote"));
        service = new WorkspaceService(tmp.resolve("workspaces"), remote.url());
    }


    @AfterEach
    void tearDown()
    {
        service.closeAll();
        remote.close();
    }


    private Workspace open(long aUser)
    {
        return service.workspace(aUser, "main", "U" + aUser, "u" + aUser + "@x.test", null);
    }


    @Test
    void anUntouchedCleanWorkspaceIsDeletedAfterTheRetentionPeriod() throws IOException
    {
        open(8);
        open(9);
        WorkspaceRetention retention = new WorkspaceRetention(service, Duration.ofDays(90));
        WorkspaceRetention.Report now = retention.sweep(Instant.now());
        assertEquals(List.of(), now.deleted());
        assertEquals(2, now.kept().size());
        assertTrue(now.bytesInUse() > 0);

        WorkspaceRetention.Report later = retention.sweep(Instant.now().plus(Duration.ofDays(91)));
        assertEquals(2, later.deleted().size());
        assertFalse(Files.exists(service.directoryFor(8)));
        assertFalse(Files.exists(service.directoryFor(9)));
        assertEquals(0, later.bytesInUse());
    }


    @Test
    void uncommittedOrUnpushedWorkIsNeverDeleted() throws IOException
    {
        Workspace uncommitted = open(8);
        uncommitted.createBranch("f", null);
        uncommitted.write(RemoteRepo.CHECK_A, "x".getBytes(StandardCharsets.UTF_8),
                Workspace.blobId(uncommitted.read(RemoteRepo.CHECK_A).orElseThrow()));

        Workspace unpushed = open(9);
        unpushed.createBranch("g", null);
        unpushed.write(RemoteRepo.CHECK_A, "y".getBytes(StandardCharsets.UTF_8),
                Workspace.blobId(unpushed.read(RemoteRepo.CHECK_A).orElseThrow()));
        unpushed.commit("local only", "U9", "u9@x.test");

        Workspace pushed = open(10);
        pushed.createBranch("h", null);
        pushed.write(RemoteRepo.CHECK_A, "z".getBytes(StandardCharsets.UTF_8),
                Workspace.blobId(pushed.read(RemoteRepo.CHECK_A).orElseThrow()));
        pushed.commit("pushed", "U10", "u10@x.test");
        pushed.push(null);

        WorkspaceRetention retention = new WorkspaceRetention(service, Duration.ofDays(1));
        WorkspaceRetention.Report r = retention.sweep(Instant.now().plus(Duration.ofDays(400)));
        assertEquals(List.of(10L), r.deleted(), "only the pushed-and-clean one goes");
        assertEquals(List.of(8L, 9L), r.kept());
        assertTrue(Files.exists(service.directoryFor(8)));
        assertTrue(Files.exists(service.directoryFor(9)));
    }


    @Test
    void zeroDaysDisablesTheSweepAndTheHooksDirMustStayEmpty() throws IOException
    {
        open(8);
        WorkspaceRetention off = new WorkspaceRetention(service, Duration.ZERO);
        assertEquals(List.of(), off.sweep(Instant.now().plus(Duration.ofDays(9999))).deleted());
        assertTrue(Files.exists(service.directoryFor(8)));

        Files.writeString(service.hooksDir().resolve("pre-commit"), "#!/bin/sh\n");
        IOException e = assertThrows(IOException.class,
                () -> new WorkspaceService(tmp.resolve("workspaces"), remote.url()));
        assertTrue(e.getMessage().contains("EMPTY"), e.getMessage());
    }


    @Test
    void workspacesReopenFromDiskAfterARestart() throws IOException
    {
        Workspace first = open(8);
        first.createBranch("f", null);
        service.closeAll();
        WorkspaceService restarted = new WorkspaceService(tmp.resolve("workspaces"), remote.url());
        try
        {
            Workspace again = restarted.workspace(8, "main", "U8", "u8@x.test", null);
            assertEquals("f", again.currentBranch(), "the next session resumes the branch");
            assertEquals(List.of(8L), restarted.userIdsOnDisk());
        }
        finally
        {
            restarted.closeAll();
        }
    }


    @Test
    void aWorkspaceInUseIsKeptAndADeletedOneIsRetired() throws Exception
    {
        // security review L10: the check and the deletion happen in ONE lock hold; a workspace
        // whose lock a request holds is in use
        Workspace ws = open(8);
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread request = new Thread(() ->
        {
            ws.lock().lock();
            try
            {
                locked.countDown();
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
        request.start();
        locked.await();
        WorkspaceRetention retention = new WorkspaceRetention(service, Duration.ofDays(1));
        Instant late = Instant.now().plus(Duration.ofDays(400));
        assertEquals(List.of(8L), retention.sweep(late).kept(), "held by a request: kept");
        assertFalse(ws.isRetired());
        release.countDown();
        request.join(10_000);
        assertEquals(List.of(8L), retention.sweep(late).deleted());
        assertTrue(ws.isRetired(), "a request still holding the old object finds it retired");
        assertFalse(Files.exists(service.directoryFor(8)));
    }
}
