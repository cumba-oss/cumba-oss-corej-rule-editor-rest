package net.cumba.corej.editor.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.config.EditorProperties.SeedType;
import net.cumba.corej.editor.testing.TestStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * The start-up seed (owner, 2026-10-08) against a fake source — no test reaches the network: absent
 * → seeded → open without a restart; an older format re-seeded, a newer one never; a changed tag
 * re-seeds a store this instance seeded, a store mounted by hand is left alone; NONE; a failure is
 * a reason and the service keeps running; the API key never reaches a reason or a log line.
 */
class StoreSeedingTest
{

    static final String KEY = "s3cr3t-API-KEY-0815";

    /** A seed supplement REPLACED line, as {@code StoreSeedReport.details()} words it. */
    static final String DETAIL = "seed supplement REPLACED the existing store's copy:"
            + " standards/sendig/dart-1-2: its source-served copy was corrected under another"
            + " errata register and is not carried - the E14 owner 2026-10-07 seed supplement"
            + " replaces it";

    /** A source writing the fixture store; optionally blocking until released, counting calls. */
    static class FakeSource implements StoreSeedSource
    {

        final AtomicInteger calls = new AtomicInteger();

        final CountDownLatch release = new CountDownLatch(1);

        final CountDownLatch entered = new CountDownLatch(1);

        boolean block;

        volatile Request last;

        @Override
        public Outcome seed(Request aRequest, Path aStaging) throws IOException
        {
            calls.incrementAndGet();
            last = aRequest;
            entered.countDown();
            if (block)
            {
                try
                {
                    assertTrue(release.await(30, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            write(aRequest, aStaging);
            return new Outcome("2 products, 1 CT package", 2, 1, List.of("one warning"),
                    "fake@" + aRequest.tag());
        }


        void write(Request aRequest, Path aStaging) throws IOException
        {
            TestStore.write(aStaging, "seeded " + aRequest.type() + " " + aRequest.tag());
        }
    }

    static EditorProperties props(SeedType aType, String aTag, String aKey)
    {
        EditorProperties p = new EditorProperties();
        p.getStore().getSeed().setType(aType);
        p.getStore().getSeed().setPklTag(aTag);
        p.getStore().getSeed().setApiKey(aKey);
        return p;
    }


    static EditorProperties props(SeedType aType, String aTag)
    {
        return props(aType, aTag, null);
    }


    static void await(Optional<CompletableFuture<Void>> aRun) throws Exception
    {
        assertTrue(aRun.isPresent(), "a seed was started");
        aRun.get().get(30, TimeUnit.SECONDS);
    }


    static String provenance(EngineStore aStore)
    {
        return aStore.store().orElseThrow().manifest().provenance().get(0).ref();
    }


    static void writeFormat(Path aFile, int aVersion) throws IOException
    {
        try (OutputStream out = Files.newOutputStream(aFile);
                ZipOutputStream zip = new ZipOutputStream(out))
        {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(("{\"formatVersion\":" + aVersion + "}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }


    static List<String> names(Path aDir) throws IOException
    {
        try (Stream<Path> s = Files.list(aDir))
        {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }


    @Test
    void anAbsentStoreIsSeededInTheBackgroundAndOpensWithoutARestart(@TempDir Path aDir)
        throws Exception
    {
        Path file = aDir.resolve("vol").resolve("metadata-store.zip");
        FakeSource source = new FakeSource();
        source.block = true;
        try (EngineStore store = new EngineStore(file))
        {
            assertEquals(EngineStore.Condition.ABSENT, store.condition());
            long generation = store.generation();
            StoreSeeding seeding = new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"),
                    source);
            Optional<CompletableFuture<Void>> run = seeding.start();
            // the service serves while the seed runs: no store, the reason says why
            assertTrue(source.entered.await(30, TimeUnit.SECONDS));
            assertFalse(store.available());
            assertEquals("metadata store is being seeded (GITHUB_PKL v0.17.1)…",
                    store.unavailableReason());
            assertEquals(EngineStore.Condition.SEEDING, store.condition());
            assertEquals(new EngineStore.Status(false, -1,
                    "metadata store is being seeded (GITHUB_PKL v0.17.1)…", true,
                    "seeding (GITHUB_PKL v0.17.1)…"), store.status());
            assertFalse(Files.exists(file), "nothing at the final path while seeding");
            source.release.countDown();
            await(run);
            assertTrue(store.available(), store.unavailableReason());
            assertEquals("", store.unavailableReason());
            assertEquals(EngineStore.Condition.OPEN, store.condition());
            assertFalse(store.seeding());
            assertEquals("", store.seedStatus(), "a complete seed leaves nothing to say");
            assertTrue(store.generation() > generation, "the generation moved");
            assertEquals("seeded GITHUB_PKL v0.17.1", provenance(store));
            assertEquals(new StoreSeedSource.Request(SeedType.GITHUB_PKL, "v0.17.1", null),
                    source.last);
            StoreSeeding.Sidecar sidecar = StoreSeeding.readSidecar(file).orElseThrow();
            assertEquals("GITHUB_PKL", sidecar.type());
            assertEquals("v0.17.1", sidecar.tag());
            assertEquals("fake@v0.17.1", sidecar.source());
            assertFalse(sidecar.seededAt().isBlank());
            assertTrue(sidecar.complete(), "recorded complete");
            assertNull(sidecar.pending());
            assertEquals(
                    List.of("metadata-store.zip", "metadata-store.zip.seed.json",
                            "metadata-store.zip.seed.lock"),
                    names(file.getParent()), "no staging file or scratch is left behind");
            assertTrue(seeding.start().isEmpty(), "one seed per start");
        }
    }


    @Test
    void anOlderFormatIsReseededANewerOneNever(@TempDir Path aDir) throws Exception
    {
        Path older = aDir.resolve("older.zip");
        writeFormat(older, 4);
        FakeSource source = new FakeSource();
        try (EngineStore store = new EngineStore(older))
        {
            assertEquals(EngineStore.Condition.OLDER_FORMAT, store.condition());
            assertTrue(store.formatMismatch());
            await(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"), source).start());
            assertTrue(store.available(), store.unavailableReason());
            assertFalse(store.formatMismatch());
        }
        Path newer = aDir.resolve("newer.zip");
        writeFormat(newer, 99);
        byte[] before = Files.readAllBytes(newer);
        try (EngineStore store = new EngineStore(newer))
        {
            assertEquals(EngineStore.Condition.NEWER_FORMAT, store.condition());
            assertTrue(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"), source)
                    .start().isEmpty());
            assertFalse(store.available());
            assertTrue(store.unavailableReason().contains("newer build"),
                    store.unavailableReason());
        }
        assertArrayEquals(before, Files.readAllBytes(newer), "a newer store is never replaced");
        assertEquals(1, source.calls.get());
    }


    @Test
    void aChangedTagReseedsAStoreThisInstanceSeededAndTheOldOneServesMeanwhile(@TempDir Path aDir)
        throws Exception
    {
        Path file = aDir.resolve("metadata-store.zip");
        TestStore.write(file, "old");
        StoreSeeding.writeSidecar(file,
                new StoreSeeding.Sidecar("GITHUB_PKL", "v0.16.0", "2026-01-01T00:00:00Z", null));
        FakeSource source = new FakeSource();
        source.block = true;
        try (EngineStore store = new EngineStore(file))
        {
            long generation = store.generation();
            Optional<CompletableFuture<Void>> run = new StoreSeeding(store,
                    props(SeedType.GITHUB_PKL, "v0.17.1"), source).start();
            assertTrue(source.entered.await(30, TimeUnit.SECONDS));
            assertTrue(store.available(), "the previous store serves until the new one is in");
            assertTrue(store.seeding(), "seeding shows while the old store serves (LOW-4)");
            assertEquals("seeding (GITHUB_PKL v0.17.1)…", store.seedStatus());
            assertEquals("", store.unavailableReason());
            assertEquals("old", provenance(store));
            assertEquals(generation, store.generation());
            source.release.countDown();
            await(run);
            assertEquals("seeded GITHUB_PKL v0.17.1", provenance(store));
            assertFalse(store.seeding());
            assertEquals("", store.seedStatus());
            assertTrue(store.generation() > generation);
            assertEquals("v0.17.1", StoreSeeding.readSidecar(file).orElseThrow().tag());
        }
        // the same source again: nothing to do
        try (EngineStore store = new EngineStore(file))
        {
            assertTrue(new StoreSeeding(store, props(SeedType.GITHUB_PKL, " v0.17.1 "), source)
                    .start().isEmpty());
        }
        // another TYPE re-seeds too
        FakeSource api = new FakeSource();
        try (EngineStore store = new EngineStore(file))
        {
            await(new StoreSeeding(store, props(SeedType.API, "v0.17.1", KEY), api).start());
            assertEquals("seeded API null", provenance(store));
            StoreSeeding.Sidecar sidecar = StoreSeeding.readSidecar(file).orElseThrow();
            assertEquals("API", sidecar.type());
            assertNull(sidecar.tag(), "API has no tag");
            assertEquals(KEY, api.last.apiKey(), "the key reaches the source");
            assertFalse(api.last.toString().contains(KEY), "but never its toString");
        }
        // API stays API whatever the pkl-tag says
        try (EngineStore store = new EngineStore(file))
        {
            assertTrue(
                    new StoreSeeding(store, props(SeedType.API, "v9", KEY), api).start().isEmpty());
        }
    }


    @Test
    void aStoreMountedByHandIsLeftAloneAndNoneNeverSeeds(@TempDir Path aDir) throws Exception
    {
        Path file = aDir.resolve("metadata-store.zip");
        TestStore.write(file, "by hand");
        FakeSource source = new FakeSource();
        try (EngineStore store = new EngineStore(file))
        {
            assertTrue(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"), source)
                    .start().isEmpty());
            assertEquals("by hand", provenance(store));
        }
        // an unreadable seed record counts as none
        Files.writeString(StoreSeeding.sidecarOf(file), "{not json", StandardCharsets.UTF_8);
        assertTrue(StoreSeeding.readSidecar(file).isEmpty());
        try (EngineStore store = new EngineStore(file))
        {
            assertTrue(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"), source)
                    .start().isEmpty());
        }
        Path absent = aDir.resolve("absent.zip");
        try (EngineStore store = new EngineStore(absent))
        {
            assertTrue(new StoreSeeding(store, props(SeedType.NONE, "v0.17.1"), source).start()
                    .isEmpty());
            assertEquals("the configured metadata store file does not exist",
                    store.unavailableReason(), "NONE: today's behaviour");
        }
        try (EngineStore store = new EngineStore((Path) null))
        {
            assertTrue(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"), source)
                    .start().isEmpty(), "no file configured: nothing to seed into");
        }
        assertFalse(Files.exists(absent));
        assertEquals(0, source.calls.get());
    }


    @Test
    void theDecisionTable()
    {
        Path p = Path.of("store.zip");
        StoreSeeding.Sidecar pkl = new StoreSeeding.Sidecar("GITHUB_PKL", "v1", "t", null);
        assertTrue(StoreSeeding.decide(SeedType.GITHUB_PKL, "v1", p, EngineStore.Condition.ABSENT,
                Optional.empty()).seed());
        assertTrue(StoreSeeding
                .decide(SeedType.API, null, p, EngineStore.Condition.OLDER_FORMAT, Optional.of(pkl))
                .seed());
        assertFalse(StoreSeeding.decide(SeedType.GITHUB_PKL, "v2", p,
                EngineStore.Condition.NEWER_FORMAT, Optional.of(pkl)).seed());
        assertFalse(StoreSeeding.decide(SeedType.GITHUB_PKL, "v2", p,
                EngineStore.Condition.UNREADABLE, Optional.of(pkl)).seed());
        assertTrue(StoreSeeding
                .decide(SeedType.GITHUB_PKL, "v2", p, EngineStore.Condition.OPEN, Optional.of(pkl))
                .seed());
        assertFalse(StoreSeeding
                .decide(SeedType.GITHUB_PKL, "v1", p, EngineStore.Condition.OPEN, Optional.of(pkl))
                .seed());
        assertFalse(StoreSeeding
                .decide(SeedType.GITHUB_PKL, "v2", p, EngineStore.Condition.OPEN, Optional.empty())
                .seed());
        assertFalse(StoreSeeding
                .decide(SeedType.NONE, "v1", p, EngineStore.Condition.ABSENT, Optional.empty())
                .seed());
        assertFalse(StoreSeeding.decide(SeedType.GITHUB_PKL, "v1", null,
                EngineStore.Condition.NOT_CONFIGURED, Optional.empty()).seed());
        assertFalse(StoreSeeding.decide(SeedType.GITHUB_PKL, "v1", p, EngineStore.Condition.SEEDING,
                Optional.empty()).seed());
    }


    @Test
    void aFailedSeedIsAReasonAndTheServiceRunsOn(@TempDir Path aDir) throws Exception
    {
        Path file = aDir.resolve("metadata-store.zip");
        StoreSeedSource failing = (aRequest, aStaging) ->
        {
            Files.writeString(aStaging, "half");
            throw new IOException("download of " + file + " failed");
        };
        try (EngineStore store = new EngineStore(file))
        {
            await(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"), failing).start());
            assertFalse(store.available());
            assertEquals(EngineStore.Condition.SEED_FAILED, store.condition());
            assertEquals(
                    "metadata store seeding failed (GITHUB_PKL v0.17.1): download of"
                            + " metadata-store.zip failed",
                    store.unavailableReason(),
                    "the reason names the file, never the server's path");
            assertFalse(store.seeding());
            assertEquals("seed failed (GITHUB_PKL v0.17.1): download of metadata-store.zip failed",
                    store.seedStatus());
        }
        assertEquals(List.of("metadata-store.zip.seed.lock"), names(aDir),
                "no half-written store, no staging file (the lock file stays)");
        // a staged file that is not a store replaces nothing
        Path present = aDir.resolve("older.zip");
        writeFormat(present, 4);
        byte[] before = Files.readAllBytes(present);
        FakeSource garbage = new FakeSource()
        {

            @Override
            void write(Request aRequest, Path aStaging) throws IOException
            {
                Files.writeString(aStaging, "not a zip");
            }
        };
        try (EngineStore store = new EngineStore(present))
        {
            await(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"), garbage).start());
            assertFalse(store.available());
            assertTrue(store.unavailableReason().startsWith("metadata store seeding failed"),
                    store.unavailableReason());
        }
        assertArrayEquals(before, Files.readAllBytes(present));
        assertEquals(List.of("metadata-store.zip.seed.lock", "older.zip", "older.zip.seed.lock"),
                names(aDir));
        // a source that yields nothing publishes nothing
        StoreSeedSource empty = (aRequest, aStaging) ->
        {
            TestStore.write(aStaging);
            return new StoreSeedSource.Outcome("nothing", 0, 0, List.of(), null);
        };
        try (EngineStore store = new EngineStore(file))
        {
            await(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"), empty).start());
            assertTrue(store.unavailableReason().endsWith("the seed source yielded no metadata"),
                    store.unavailableReason());
        }
        assertFalse(Files.exists(file));
    }


    @Test
    void anUnusableTagNeverReachesTheSourceAndLeftoversAreSwept(@TempDir Path aDir) throws Exception
    {
        Path file = aDir.resolve("metadata-store.zip");
        Files.writeString(aDir.resolve("metadata-store.zip.seed-staging-123"), "killed");
        Path scratch = aDir.resolve("metadata-store.zip.seed-scratch-42");
        Files.createDirectories(scratch.resolve("cache"));
        Files.writeString(scratch.resolve("cache").resolve("x.pkl"), "x");
        Files.writeString(aDir.resolve("metadata-store.zip.seed.json7.tmp"), "{");
        Files.writeString(aDir.resolve("other.zip.seed-staging-1"), "not ours");
        Files.createDirectories(aDir.resolve(".seed-scratch-9"));
        FakeSource source = new FakeSource();
        try (EngineStore store = new EngineStore(file))
        {
            await(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "../../evil"), source)
                    .start());
            assertTrue(store.unavailableReason().contains("not a usable tag"),
                    store.unavailableReason());
        }
        assertEquals(0, source.calls.get());
        assertEquals(List.of(".seed-scratch-9", "metadata-store.zip.seed.lock",
                "other.zip.seed-staging-1"), names(aDir), "only this store's leftovers go");
    }


    @Test
    void theApiKeyNeverReachesAReasonOrALogLine(@TempDir Path aDir) throws Exception
    {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try
        {
            Path file = aDir.resolve("metadata-store.zip");
            StoreSeedSource leaky = (aRequest, aStaging) ->
            {
                throw new IOException("401 for api-key " + aRequest.apiKey());
            };
            EditorProperties p = props(SeedType.API, "v0.17.1", KEY);
            try (EngineStore store = new EngineStore(file))
            {
                StoreSeeding seeding = new StoreSeeding(store, p, leaky);
                await(seeding.start());
                assertEquals("metadata store seeding failed (API): 401 for api-key ***",
                        store.unavailableReason());
                assertEquals("API", seeding.label());
            }
            // the real source refuses a missing key before any network access
            try (EngineStore store = new EngineStore(file))
            {
                await(new StoreSeeding(store, props(SeedType.API, "v0.17.1", " "),
                        new EngineStoreSeedSource()).start());
                assertTrue(store.unavailableReason().contains("no CDISC Library API key"),
                        store.unavailableReason());
            }
            assertFalse(p.getStore().getSeed().toString().contains(KEY));
            assertTrue(p.getStore().getSeed().toString().contains("apiKey=<set>"));
            assertFalse(appender.list.isEmpty(), "the seeding logged");
            for (ILoggingEvent e : appender.list)
            {
                assertFalse(e.getFormattedMessage().contains(KEY), e.getFormattedMessage());
                assertTrue(e.getThrowableProxy() == null
                        || !String.valueOf(e.getThrowableProxy().getMessage()).contains(KEY));
            }
        }
        finally
        {
            root.detachAppender(appender);
        }
    }


    @Test
    void theProductionSourceFailsCleanlyWithoutANetwork(@TempDir Path aDir) throws Exception
    {
        Path staging = aDir.resolve("metadata-store.zip.1.seeding");
        EngineStoreSeedSource source = new EngineStoreSeedSource("http://localhost:1/cdisc",
                "http://localhost:1/api");
        assertThrows(IOException.class, () -> source
                .seed(new StoreSeedSource.Request(SeedType.GITHUB_PKL, "v0.17.1", null), staging));
        assertEquals(List.of(), names(aDir), "the scratch is removed, nothing is staged");
    }


    /** A source writing "seed N" each call, reporting {@code aFailures} fetch failures. */
    static StoreSeedSource partial(AtomicInteger aCalls, List<String> aFailures)
    {
        return (aRequest, aStaging) ->
        {
            TestStore.write(aStaging, "seed " + aCalls.incrementAndGet());
            return new StoreSeedSource.Outcome("partial", 2, 1, List.of("CT-MISSED"), aFailures,
                    List.of("a content warning"), List.of(), "fake");
        };
    }


    /**
     * MED-1: a seed whose fetches failed is published only when no store is open, recorded
     * incomplete and re-seeded at the next start; over an open store it is dropped.
     */
    @Test
    void aPartialSeedIsPublishedOnlyWithoutAStoreAndReseededAtTheNextStart(@TempDir Path aDir)
        throws Exception
    {
        Path file = aDir.resolve("metadata-store.zip");
        AtomicInteger calls = new AtomicInteger();
        List<String> failures = List.of(
                "CDASH 2.2: fetching /mdr/cdash/2-2 failed (timeout) -" + " skipped",
                "sdtmct-2024-03-29: source failed delivering the CT package (503)");
        try (EngineStore store = new EngineStore(file))
        {
            await(new StoreSeeding(store, props(SeedType.API, null, KEY), partial(calls, failures))
                    .start());
            assertTrue(store.available(), "better than nothing: published without a store");
            assertEquals("seed 1", provenance(store));
            assertFalse(store.seeding());
            assertEquals("incomplete (API): 2 fetches failed; re-seeded at the next start",
                    store.seedStatus());
            StoreSeeding.Sidecar sidecar = StoreSeeding.readSidecar(file).orElseThrow();
            assertTrue(sidecar.isIncomplete(), "recorded incomplete");
            assertTrue(
                    Files.readString(StoreSeeding.sidecarOf(file)).contains("\"complete\":false"));
        }
        // the next start re-seeds; still partial, so the open store is KEPT
        try (EngineStore store = new EngineStore(file))
        {
            assertTrue(
                    StoreSeeding.decide(SeedType.API, null, file, store.condition(),
                            StoreSeeding.readSidecar(file)).seed(),
                    "an incomplete seed is re-seeded");
            long generation = store.generation();
            await(new StoreSeeding(store, props(SeedType.API, null, KEY), partial(calls, failures))
                    .start());
            assertEquals(2, calls.get());
            assertEquals("seed 1", provenance(store), "a partial never replaces an open store");
            assertEquals(generation, store.generation());
            assertEquals("seed incomplete (API): 2 fetches failed; the open store was kept,"
                    + " retried at the next start", store.seedStatus());
            assertTrue(StoreSeeding.readSidecar(file).orElseThrow().isIncomplete(),
                    "recorded incomplete");
        }
        // a complete seed replaces it and the record says complete
        try (EngineStore store = new EngineStore(file))
        {
            await(new StoreSeeding(store, props(SeedType.API, null, KEY), partial(calls, List.of()))
                    .start());
            assertEquals("seed 3", provenance(store));
            assertEquals("", store.seedStatus());
            assertTrue(StoreSeeding.readSidecar(file).orElseThrow().complete(),
                    "recorded complete");
        }
        try (EngineStore store = new EngineStore(file))
        {
            assertTrue(
                    new StoreSeeding(store, props(SeedType.API, null, KEY),
                            partial(calls, List.of())).start().isEmpty(),
                    "complete: nothing to do");
        }
    }


    /**
     * PLAN-seed-sendig-dart-1-2 confirmation look L-c: a seed's detail lines — where a seed
     * supplement that REPLACED a copy is reported, never in the summary or as a warning — reach the
     * editor's log, as the CLI, the REST service and the desktop print them.
     */
    @Test
    void theSeedsDetailLinesAreLogged(@TempDir Path aDir) throws Exception
    {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try
        {
            StoreSeedSource detailed = (aRequest, aStaging) ->
            {
                TestStore.write(aStaging, "seeded with a detail");
                return new StoreSeedSource.Outcome("2 products", 2, 1, List.of(), List.of(),
                        List.of(), List.of(DETAIL), "fake");
            };
            try (EngineStore store = new EngineStore(aDir.resolve("metadata-store.zip")))
            {
                await(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"), detailed)
                        .start());
                assertEquals("seeded with a detail", provenance(store));
            }
            assertTrue(
                    appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                            .anyMatch(("metadata store seed detail: " + DETAIL)::equals),
                    "the REPLACED line is logged");
        }
        finally
        {
            root.detachAppender(appender);
        }
    }


    /** The engine reports fetch failures as text: the two wordings, and content warnings apart. */
    @Test
    void fetchFailuresAreToldApartFromContentWarnings()
    {
        StoreSeedSource.Outcome o = EngineStoreSeedSource.outcome("s", 1, 1, List.of("CT-1"),
                List.of("SDTMIG 3.4: fetching /mdr/sdtmig/3-4 failed (connect timed out) - skipped",
                        "CT-1: source failed delivering the CT package (HTTP 503) - the store"
                                + " will answer ABSENT for it",
                        "CT-2: published but not obtainable from this source - the store will"
                                + " answer ABSENT for it",
                        "label or prompt ends in a footnote marker (owner rule 2026-10-03): X",
                        "SDTMIG 3.4: /mdr/sdtmig/3-4 projected with no classes, datasets or data"
                                + " structures - the source may not have served the expanded"
                                + " document"),
                List.of(DETAIL), null);
        assertEquals(2, o.fetchFailures().size(), o.fetchFailures().toString());
        assertEquals(3, o.warnings().size());
        assertEquals(List.of("CT-1"), o.ctPackagesMissed());
        assertEquals(List.of(DETAIL), o.details(),
                "L-c: the detail lines pass through, apart from the warnings");
        assertFalse(o.complete());
        assertTrue(new StoreSeedSource.Outcome("s", 1, 1, List.of("w"), null).complete());
    }


    /**
     * MED-2: a PENDING record (the process died between the move and the record) never matches the
     * configured source, so the next start re-seeds.
     */
    @Test
    void aPendingSeedRecordIsReseeded(@TempDir Path aDir) throws Exception
    {
        Path file = aDir.resolve("metadata-store.zip");
        TestStore.write(file, "moved, never recorded");
        StoreSeeding.writeSidecar(file,
                StoreSeeding.Sidecar.pending("GITHUB_PKL", "v0.17.1", "2026-10-08T00:00:00Z"));
        StoreSeeding.Sidecar pending = StoreSeeding.readSidecar(file).orElseThrow();
        assertFalse(StoreSeeding.sameSource(pending, SeedType.GITHUB_PKL, "v0.17.1"));
        assertTrue(StoreSeeding.decide(SeedType.GITHUB_PKL, "v0.17.1", file,
                EngineStore.Condition.OPEN, Optional.of(pending)).seed());
        FakeSource source = new FakeSource();
        try (EngineStore store = new EngineStore(file))
        {
            await(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"), source).start());
            assertEquals("seeded GITHUB_PKL v0.17.1", provenance(store));
        }
        StoreSeeding.Sidecar done = StoreSeeding.readSidecar(file).orElseThrow();
        assertNull(done.pending(), "the real record replaced the PENDING one");
        assertTrue(StoreSeeding.sameSource(done, SeedType.GITHUB_PKL, "v0.17.1"));
    }


    /** MED-3: an Error (not OOM) ends in SEED_FAILED, never a seed stuck in SEEDING. */
    @Test
    void anErrorEndsInSeedFailedNeverStuckSeeding(@TempDir Path aDir) throws Exception
    {
        Path file = aDir.resolve("metadata-store.zip");
        StoreSeedSource broken = (aRequest, aStaging) ->
        {
            throw new LinkageError("class missing in " + aStaging);
        };
        try (EngineStore store = new EngineStore(file))
        {
            await(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"), broken).start());
            assertEquals(EngineStore.Condition.SEED_FAILED, store.condition());
            assertFalse(store.seeding());
            assertTrue(
                    store.seedStatus().startsWith(
                            "seed failed (GITHUB_PKL v0.17.1): LinkageError: class missing in"),
                    store.seedStatus());
            assertFalse(store.seedStatus().contains(aDir.toString()), store.seedStatus());
            assertTrue(store.unavailableReason().startsWith("metadata store seeding failed"));
        }
    }


    /** LOW-4: a failed re-seed shows while the old store serves — without the key or a path. */
    @Test
    void aFailedReseedIsVisibleWithAnOpenStoreWithoutKeyOrPath(@TempDir Path aDir) throws Exception
    {
        Path file = aDir.resolve("metadata-store.zip");
        TestStore.write(file, "old");
        StoreSeeding.writeSidecar(file,
                new StoreSeeding.Sidecar("GITHUB_PKL", "v0.16.0", "2026-01-01T00:00:00Z", null));
        StoreSeedSource leaky = (aRequest, aStaging) ->
        {
            throw new IOException("401 for " + aRequest.apiKey() + " writing " + aStaging);
        };
        try (EngineStore store = new EngineStore(file))
        {
            await(new StoreSeeding(store, props(SeedType.API, null, KEY), leaky).start());
            assertTrue(store.available(), "the old store keeps serving");
            assertEquals("old", provenance(store));
            assertEquals("", store.unavailableReason(), "storeReason keeps its meaning");
            assertEquals(EngineStore.Condition.OPEN, store.condition());
            String status = store.status().seedStatus();
            assertTrue(status.startsWith("seed failed (API): 401 for *** writing "), status);
            assertFalse(status.contains(KEY), status);
            assertFalse(status.contains(aDir.toString()), status);
            assertFalse(store.status().seeding());
        }
    }


    /** LOW-5: a held seed lock means no seed (and no sweep); the status says why. */
    @Test
    void aHeldSeedLockMeansNoSeed(@TempDir Path aDir) throws Exception
    {
        Path file = aDir.resolve("metadata-store.zip");
        Path leftover = aDir.resolve("metadata-store.zip.seed-staging-1");
        Files.writeString(leftover, "another process's staging");
        FakeSource source = new FakeSource();
        try (StoreSeeding.SeedLock other = StoreSeeding.SeedLock.tryAcquire(file).orElseThrow();
                EngineStore store = new EngineStore(file))
        {
            assertNotNull(other, "another holder");
            assertTrue(StoreSeeding.SeedLock.tryAcquire(file).isEmpty(), "the lock is exclusive");
            assertTrue(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"), source)
                    .start().isEmpty());
            assertEquals(EngineStore.Condition.SEED_FAILED, store.condition());
            assertEquals("seed failed (GITHUB_PKL v0.17.1): another process is seeding this store",
                    store.seedStatus());
            assertFalse(store.seeding());
            assertTrue(Files.exists(leftover), "never swept under someone else's lock");
        }
        assertEquals(0, source.calls.get());
        // released: the next start sweeps and seeds
        try (EngineStore store = new EngineStore(file))
        {
            await(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"), source).start());
            assertTrue(store.available());
        }
        assertFalse(Files.exists(leftover));
        assertTrue(StoreSeeding.SeedLock.tryAcquire(file).map(l ->
        {
            l.close();
            return true;
        }).orElse(false), "the seed released its lock");
    }


    /** LOW-5: leftovers are swept on every start, NONE included; NONE without any adds no file. */
    @Test
    void noneSweepsLeftoversAndOtherwiseLeavesTheDirectoryAlone(@TempDir Path aDir) throws Exception
    {
        Path file = aDir.resolve("metadata-store.zip");
        TestStore.write(file, "by hand");
        FakeSource source = new FakeSource();
        try (EngineStore store = new EngineStore(file))
        {
            assertTrue(
                    new StoreSeeding(store, props(SeedType.NONE, null), source).start().isEmpty());
        }
        assertEquals(List.of("metadata-store.zip"), names(aDir), "nothing to sweep: no lock file");
        Files.writeString(aDir.resolve("metadata-store.zip.seed-staging-5"), "killed");
        Files.createDirectories(aDir.resolve("metadata-store.zip.seed-scratch-5").resolve("x"));
        try (EngineStore store = new EngineStore(file))
        {
            assertTrue(
                    new StoreSeeding(store, props(SeedType.NONE, null), source).start().isEmpty());
            assertEquals("by hand", provenance(store));
            assertEquals("", store.seedStatus());
        }
        assertEquals(List.of("metadata-store.zip", "metadata-store.zip.seed.lock"), names(aDir));
        assertEquals(0, source.calls.get());
    }


    /** LOW-6: a directory at the store path is never seeded (no download that cannot land). */
    @Test
    void aDirectoryAtTheStorePathIsNeverSeeded(@TempDir Path aDir) throws Exception
    {
        Path file = Files.createDirectories(aDir.resolve("metadata-store.zip"));
        FakeSource source = new FakeSource();
        try (EngineStore store = new EngineStore(file))
        {
            assertEquals(EngineStore.Condition.UNREADABLE, store.condition());
            assertTrue(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"), source)
                    .start().isEmpty());
            assertEquals("the configured store path is a directory, not a file",
                    store.unavailableReason());
        }
        assertEquals(0, source.calls.get());
    }


    /** LOW-8: a seed record with a field this build does not know is still a seed record. */
    @Test
    void anUnknownSidecarFieldIsTolerated(@TempDir Path aDir) throws Exception
    {
        Path file = aDir.resolve("metadata-store.zip");
        TestStore.write(file, "seeded");
        Files.writeString(StoreSeeding.sidecarOf(file),
                "{\"type\":\"GITHUB_PKL\",\"tag\":\"v0.17.1\",\"seededAt\":\"t\","
                        + "\"source\":null,\"complete\":true,\"futureField\":{\"a\":1}}",
                StandardCharsets.UTF_8);
        StoreSeeding.Sidecar sidecar = StoreSeeding.readSidecar(file).orElseThrow();
        assertEquals("v0.17.1", sidecar.tag());
        FakeSource source = new FakeSource();
        try (EngineStore store = new EngineStore(file))
        {
            assertTrue(new StoreSeeding(store, props(SeedType.GITHUB_PKL, "v0.17.1"), source)
                    .start().isEmpty(), "the configured source: nothing to do");
        }
        assertEquals(0, source.calls.get());
    }
}
