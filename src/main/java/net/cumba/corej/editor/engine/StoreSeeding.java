package net.cumba.corej.editor.engine;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.config.EditorProperties.SeedType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The container seeds its own metadata store at START-UP (owner, 2026-10-08): {@code
 * editor.store.seed.type} = {@code GITHUB_PKL} (default; the pickles of cdisc-rules-engine at
 * {@code pkl-tag}), {@code API} (the CDISC Library with {@code api-key}) or {@code NONE} (mounted
 * by hand).
 *
 * <p>
 * <b>When</b> ({@link #decide}): the store file is absent, or refused for an OLDER format (with or
 * without a seed record); or this instance seeded it (the {@linkplain Sidecar sidecar} beside it
 * says so) from another type or tag than the configured one, or its last seed was INCOMPLETE (a
 * fetch failed) or never recorded (PENDING: the process died between the move and the record). A
 * store written by a NEWER build is never replaced; a store of the current format without a sidecar
 * (mounted by hand) or one that is unreadable (a directory included) is left alone.
 * </p>
 *
 * <p>
 * <b>How</b>: in the background — the service starts and serves at once. Without an open store the
 * engine info says {@code "metadata store is being seeded (GITHUB_PKL v0.17.1)…"}, a failure says
 * why, and the service keeps running without a store; an open store (a re-seed) keeps serving until
 * the new one replaces it. Whatever the store's availability, {@code seeding} and
 * {@code seedStatus} on {@code /api/info} tell the last or ongoing seed's state. The whole seed
 * holds a file lock ({@code <store>.seed.lock}): a second process on the same volume does not seed.
 * The store is written to a staging file in the SAME directory, opened to prove it is a store; a
 * PENDING sidecar is written; the staging file is moved over the final path atomically,
 * {@link EngineStore#reopen} publishes it at once (analysis and completion use it without a
 * restart), and only then the real sidecar replaces the PENDING one.
 * </p>
 *
 * <p>
 * An INCOMPLETE seed (the source failed delivering a product or CT package, MED-1) is published
 * only when no store is open (better than nothing), recorded {@code "complete": false} so the next
 * start re-seeds; over an open store it is dropped and the old store kept.
 * </p>
 *
 * <p>
 * ⛔ The API key never reaches a log line, a reason or {@code /api/info}: a failure's message is
 * scrubbed of it, and of the store's directory (security review L8).
 * </p>
 */
@Component
public class StoreSeeding
{

    static final Logger LOG = LoggerFactory.getLogger(StoreSeeding.class);

    /** The sidecar's suffix: {@code metadata-store.zip.seed.json}. */
    static final String SIDECAR_SUFFIX = ".seed.json";

    /** The lock's suffix: {@code metadata-store.zip.seed.lock} (left in place, never deleted). */
    static final String LOCK_SUFFIX = ".seed.lock";

    /** The staging file's infix: {@code metadata-store.zip.seed-staging-<random>}. */
    static final String STAGING_INFIX = ".seed-staging-";

    /** The scratch directory's infix: {@code metadata-store.zip.seed-scratch-<random>}. */
    static final String SCRATCH_INFIX = ".seed-scratch-";

    /** What a held lock reports. */
    static final String LOCK_HELD = "another process is seeding this store";

    /** A cdisc-rules-engine tag as it may appear in the archive URL. */
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    /** Reads a sidecar written by any build: an unknown field is ignored (LOW-8). */
    private static final JsonMapper SIDECAR_READER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /**
     * What {@link StoreSeeding} records beside a store it seeded.
     *
     * @param type
     *            the seed type
     * @param tag
     *            the cdisc-rules-engine tag ({@code GITHUB_PKL}), else null
     * @param seededAt
     *            when (ISO-8601)
     * @param source
     *            the seeder's source reference (repository at tag and commit), or null
     * @param complete
     *            false when a fetch failed (the next start re-seeds); null (an older record) counts
     *            as complete
     * @param pending
     *            true while the store is being moved into place: a record left PENDING never
     *            matches the configured source, so the next start re-seeds
     */
    public record Sidecar(String type, @Nullable String tag, String seededAt,
            @Nullable String source, @Nullable Boolean complete, @Nullable Boolean pending)
    {

        /** A complete, recorded seed. */
        public Sidecar(String aType, @Nullable String aTag, String aSeededAt,
                @Nullable String aSource)
        {
            this(aType, aTag, aSeededAt, aSource, Boolean.TRUE, null);
        }


        /** The record written before the move. */
        static Sidecar pending(String aType, @Nullable String aTag, String aSeededAt)
        {
            return new Sidecar(aType, aTag, aSeededAt, null, null, Boolean.TRUE);
        }


        boolean isPending()
        {
            return Boolean.TRUE.equals(pending);
        }


        boolean isIncomplete()
        {
            return Boolean.FALSE.equals(complete);
        }
    }


    /** What the start-up decides. */
    public record Plan(boolean seed, String why)
    {

        static Plan skip(String aWhy)
        {
            return new Plan(false, aWhy);
        }


        static Plan seed(String aWhy)
        {
            return new Plan(true, aWhy);
        }
    }


    /** The seed lock on {@code <store>.seed.lock}, held for the whole seed (LOW-5). */
    static final class SeedLock implements AutoCloseable
    {

        private final FileChannel channel;

        private final FileLock lock;

        private SeedLock(FileChannel aChannel, FileLock aLock)
        {
            channel = aChannel;
            lock = aLock;
        }


        /**
         * Takes the lock without waiting.
         *
         * @return the lock, or empty when another process (or another seed in this JVM) holds it
         */
        static Optional<SeedLock> tryAcquire(Path aStore) throws IOException
        {
            FileChannel ch = FileChannel.open(lockOf(aStore), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            try
            {
                FileLock l = ch.tryLock();
                if (l == null)
                {
                    ch.close();
                    return Optional.empty();
                }
                return Optional.of(new SeedLock(ch, l));
            }
            catch (OverlappingFileLockException _)
            {
                ch.close();
                return Optional.empty();
            }
            catch (IOException | RuntimeException e)
            {
                ch.close();
                throw e;
            }
        }


        @Override
        public void close()
        {
            try
            {
                lock.release();
                channel.close();
            }
            catch (IOException e)
            {
                LOG.warn("seed lock not released cleanly: {}", e.getMessage());
            }
        }
    }

    private final EngineStore store;

    private final EditorProperties.Seed config;

    private final StoreSeedSource source;

    private final AtomicBoolean started = new AtomicBoolean();

    private volatile @Nullable CompletableFuture<Void> running;

    public StoreSeeding(EngineStore aStore, EditorProperties aProperties, StoreSeedSource aSource)
    {
        store = aStore;
        config = aProperties.getStore().getSeed();
        source = aSource;
    }


    /** Starts the start-up seed once the service is up (it serves while the seed runs). */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady()
    {
        start();
    }


    /**
     * Sweeps an interrupted seed's leftovers (under the lock), decides and, when a seed is due,
     * starts it on a background thread holding the lock.
     *
     * @return the running seed, completing when it is over (successfully or not — a failure is
     *         reported through {@link EngineStore#status()}, never thrown); empty when nothing was
     *         due or a seed already ran
     */
    public Optional<CompletableFuture<Void>> start()
    {
        if (!started.compareAndSet(false, true))
        {
            return Optional.empty();
        }
        Path target = store.path().orElse(null);
        Optional<SeedLock> lock = Optional.empty();
        String lockProblem = null;
        if (target != null)
        {
            try
            {
                lock = lockAndSweep(target);
                if (lock.isEmpty() && config.getType() != SeedType.NONE)
                {
                    lockProblem = LOCK_HELD;
                }
            }
            catch (IOException | RuntimeException e)
            {
                LOG.warn("metadata store seed lock not taken: {}", e.getMessage());
                lockProblem = "the seed lock was not taken: "
                        + scrub(String.valueOf(e.getMessage()), target);
            }
        }
        Plan plan = decide(config.getType(), config.getPklTag(), target, store.condition(),
                target == null ? Optional.empty() : readSidecar(target));
        LOG.info("metadata store seeding ({}, {}): {}", config.getType(), label(), plan.why());
        if (!plan.seed() || target == null)
        {
            lock.ifPresent(SeedLock::close);
            return Optional.empty();
        }
        String label = label();
        if (lockProblem != null || lock.isEmpty())
        {
            String why = lockProblem == null ? LOCK_HELD : lockProblem;
            LOG.warn("metadata store seeding ({}) not started: {}", label, why);
            failed(label, why);
            return Optional.empty();
        }
        SeedLock held = lock.get();
        store.seeding("metadata store is being seeded (" + label + ")…",
                "seeding (" + label + ")…");
        CompletableFuture<Void> done = new CompletableFuture<>();
        running = done;
        Thread.ofPlatform().daemon().name("metadata-store-seed").start(() ->
        {
            try (held)
            {
                run(target, label);
            }
            finally
            {
                done.complete(null);
            }
        });
        return Optional.of(done);
    }


    /**
     * Takes the seed lock and sweeps leftovers under it. With {@code NONE} the lock (and its file)
     * is taken only when there is something to sweep, so a hand-mounted directory gets no file.
     *
     * @return the lock, or empty when it is held elsewhere (nothing swept) or not needed
     */
    private Optional<SeedLock> lockAndSweep(Path aTarget) throws IOException
    {
        Path dir = aTarget.toAbsolutePath().getParent();
        if (dir == null)
        {
            return Optional.empty();
        }
        String name = String.valueOf(aTarget.getFileName());
        if (config.getType() == SeedType.NONE)
        {
            if (!Files.isDirectory(dir) || !hasLeftovers(dir, name))
            {
                return Optional.empty();
            }
        }
        else
        {
            Files.createDirectories(dir);
        }
        Optional<SeedLock> lock = SeedLock.tryAcquire(aTarget);
        if (lock.isPresent())
        {
            sweep(dir, name);
        }
        else
        {
            LOG.info("metadata store seed lock {} is held by another process; not swept",
                    lockOf(aTarget));
        }
        return lock;
    }


    /** The seed this instance started, if any (done or not). */
    public Optional<CompletableFuture<Void>> current()
    {
        return Optional.ofNullable(running);
    }


    /**
     * The start-up decision, from the configuration, the configured file's condition and its
     * sidecar.
     */
    static Plan decide(SeedType aType, @Nullable String aTag, @Nullable Path aTarget,
            EngineStore.Condition aCondition, Optional<Sidecar> aSidecar)
    {
        if (aType == SeedType.NONE)
        {
            return Plan.skip("seeding is off (NONE)");
        }
        if (aTarget == null)
        {
            return Plan.skip("no metadata store file is configured, nothing to seed into");
        }
        return switch (aCondition)
        {
        case ABSENT -> Plan.seed("the store file does not exist");
        case OLDER_FORMAT -> Plan.seed("the store is of an older format");
        case NEWER_FORMAT -> Plan
                .skip("the store was written by a newer build and is never replaced automatically");
        case UNREADABLE -> Plan.skip("the store is unreadable; left alone (replace it by hand"
                + " or delete it to have it seeded)");
        case OPEN -> aSidecar.map(s -> decideOpen(s, aType, aTag)).orElseGet(
                () -> Plan.skip("the store has no seed record (mounted by hand); left alone"));
        case NOT_CONFIGURED, SEEDING, SEED_FAILED -> Plan
                .skip("nothing to do (" + aCondition + ")");
        };
    }


    private static Plan decideOpen(Sidecar aSidecar, SeedType aType, @Nullable String aTag)
    {
        if (aSidecar.isPending())
        {
            return Plan.seed("the last seed was interrupted before it was recorded");
        }
        if (!sameSource(aSidecar, aType, aTag))
        {
            return Plan.seed("the store was seeded from " + aSidecar.type()
                    + (aSidecar.tag() == null ? "" : " " + aSidecar.tag())
                    + ", the configured source differs");
        }
        if (aSidecar.isIncomplete())
        {
            return Plan.seed("the last seed from the configured source was incomplete");
        }
        return Plan.skip("the store was seeded from the configured source");
    }


    /** Whether the sidecar records the configured source; a PENDING record never does. */
    static boolean sameSource(Sidecar aSidecar, SeedType aType, @Nullable String aTag)
    {
        if (aSidecar.isPending() || !aType.name().equals(aSidecar.type()))
        {
            return false;
        }
        return aType != SeedType.GITHUB_PKL || Objects.equals(strip(aTag), aSidecar.tag());
    }


    private static @Nullable String strip(@Nullable String aValue)
    {
        return aValue == null ? null : aValue.strip();
    }


    /** {@code GITHUB_PKL v0.17.1} or {@code API} — never the key. */
    String label()
    {
        SeedType type = config.getType();
        return type == SeedType.GITHUB_PKL ? type + " " + strip(config.getPklTag()) : type.name();
    }


    /** The sidecar beside {@code aStore}. */
    static Path sidecarOf(Path aStore)
    {
        return aStore.resolveSibling(aStore.getFileName() + SIDECAR_SUFFIX);
    }


    /** The lock file beside {@code aStore}. */
    static Path lockOf(Path aStore)
    {
        return aStore.resolveSibling(aStore.getFileName() + LOCK_SUFFIX);
    }


    /**
     * The scratch prefix for a staging file {@code <store>.seed-staging-…}:
     * {@code <store>.seed-scratch-}, so the sweep finds a killed run's scratch.
     */
    static String scratchPrefix(Path aStaging)
    {
        String n = String.valueOf(aStaging.getFileName());
        int at = n.indexOf(STAGING_INFIX);
        return (at > 0 ? n.substring(0, at) : n) + SCRATCH_INFIX;
    }


    /** The sidecar, when there is a readable one; an unreadable one counts as none (warned). */
    static Optional<Sidecar> readSidecar(Path aStore)
    {
        Path file = sidecarOf(aStore);
        if (!Files.isRegularFile(file))
        {
            return Optional.empty();
        }
        try
        {
            Sidecar s = SIDECAR_READER.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    Sidecar.class);
            return s == null || s.type() == null ? Optional.empty() : Optional.of(s);
        }
        catch (IOException | RuntimeException e)
        {
            LOG.warn("seed record {} unreadable, the store counts as mounted by hand: {}", file,
                    e.getMessage());
            return Optional.empty();
        }
    }


    /** Reports a failed (or not started) seed: the seed status always, the reason without store. */
    private void failed(String aLabel, String aWhy)
    {
        store.seedFailed("metadata store seeding failed (" + aLabel + "): " + aWhy,
                "seed failed (" + aLabel + "): " + aWhy);
    }


    /**
     * The seed itself, on the background thread holding the lock; every outcome ends in the
     * engine's state — an {@link Error} included, never a seed stuck in SEEDING (MED-3).
     */
    void run(Path aTarget, String aLabel)
    {
        long t0 = System.nanoTime();
        Path dir = aTarget.toAbsolutePath().getParent();
        Path staging = null;
        try
        {
            if (dir == null)
            {
                throw new IOException("the store file has no directory");
            }
            Files.createDirectories(dir);
            String tag = config.getType() == SeedType.GITHUB_PKL ? strip(config.getPklTag()) : null;
            if (tag != null && !TAG.matcher(tag).matches())
            {
                throw new IOException(
                        "editor.store.seed.pkl-tag is not a usable tag: '" + tag + "'");
            }
            staging = Files.createTempFile(dir, aTarget.getFileName() + STAGING_INFIX, "");
            Files.delete(staging); // the seeder writes it; it must not find an empty baseline
            StoreSeedSource.Outcome outcome = source.seed(
                    new StoreSeedSource.Request(config.getType(), tag, config.getApiKey()),
                    staging);
            if (outcome.products() + outcome.ctPackages() == 0)
            {
                throw new IOException("the seed source yielded no metadata");
            }
            verify(staging);
            logOutcome(outcome, aTarget);
            publish(aTarget, aLabel, tag, staging, outcome, t0);
        }
        catch (OutOfMemoryError e)
        {
            LOG.error("metadata store seeding ({}) ran out of memory: {}", aLabel, e.getMessage());
            failed(aLabel, "out of memory — give the service more heap (JAVA_OPTS -Xmx)");
        }
        catch (IOException | RuntimeException e)
        {
            String why = scrub(String.valueOf(e.getMessage()), aTarget);
            LOG.warn("metadata store seeding ({}) failed after {} s: {} ({})", aLabel,
                    (System.nanoTime() - t0) / 1_000_000_000L, why, e.getClass().getName());
            failed(aLabel, why);
        }
        catch (Error e)
        {
            String message = e.getMessage();
            String why = e.getClass().getSimpleName()
                    + (message == null ? "" : ": " + scrub(message, aTarget));
            LOG.error("metadata store seeding ({}) failed: {} ({})", aLabel, why,
                    e.getClass().getName());
            failed(aLabel, why);
        }
        finally
        {
            if (staging != null)
            {
                deleteQuietly(staging);
            }
        }
    }


    private void logOutcome(StoreSeedSource.Outcome aOutcome, Path aTarget)
    {
        for (String warning : aOutcome.warnings())
        {
            LOG.warn("metadata store seed warning: {}", scrub(warning, aTarget));
        }
        for (String missed : aOutcome.ctPackagesMissed())
        {
            LOG.warn("metadata store seed: CT package {} missing, the store answers ABSENT for it",
                    missed);
        }
        for (String failure : aOutcome.fetchFailures())
        {
            LOG.warn("metadata store seed FETCH failed: {}", scrub(failure, aTarget));
        }
        for (String detail : aOutcome.details())
        {
            LOG.info("metadata store seed detail: {}", scrub(detail, aTarget));
        }
    }


    /**
     * Publishes a verified staging file (MED-1, MED-2): an incomplete one only when no store is
     * open; a PENDING record before the move, {@link EngineStore#reopen} right after it, the real
     * record last — its failure is a warning and a status note, never a storeless instance.
     */
    private void publish(Path aTarget, String aLabel, @Nullable String aTag, Path aStaging,
            StoreSeedSource.Outcome aOutcome, long aT0)
        throws IOException
    {
        int failures = aOutcome.fetchFailures().size();
        if (!aOutcome.complete() && store.available())
        {
            String note = "seed incomplete (" + aLabel + "): " + failures
                    + " fetches failed; the open store was kept, retried at the next start";
            LOG.warn("metadata store {}", note);
            store.seedNote(note);
            return;
        }
        String type = config.getType().name();
        String now = Instant.now().toString();
        writeSidecar(aTarget, Sidecar.pending(type, aTag, now));
        moveAtomically(aStaging, aTarget);
        String status = aOutcome.complete() ? ""
                : "incomplete (" + aLabel + "): " + failures
                        + " fetches failed; re-seeded at the next start";
        store.reopen(status);
        LOG.info("metadata store seeded ({}) in {} s: {}", aLabel,
                (System.nanoTime() - aT0) / 1_000_000_000L, scrub(aOutcome.summary(), aTarget));
        try
        {
            writeSidecar(aTarget,
                    new Sidecar(type, aTag, now, aOutcome.sourceRef(), aOutcome.complete(), null));
        }
        catch (IOException | RuntimeException e)
        {
            String why = scrub(String.valueOf(e.getMessage()), aTarget);
            LOG.warn("metadata store seeded ({}) but its seed record was not written: {}", aLabel,
                    why);
            store.seedNote((status.isEmpty() ? "" : status + "; ") + "seeded (" + aLabel
                    + ") but the seed record was not written (" + why
                    + "); re-seeded at the next start");
        }
    }


    /** Opens the staged file: only a store the engine reads replaces anything. */
    @SuppressWarnings("PMD.CloseResource") // closed right here
    static void verify(Path aStaging) throws IOException
    {
        MetadataStore staged = MetadataStore.open(aStaging);
        staged.close();
    }


    /** Moves within one directory: atomic where the file system can, never a copy. */
    static void moveAtomically(Path aFrom, Path aTo) throws IOException
    {
        try
        {
            Files.move(aFrom, aTo, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException _)
        {
            Files.move(aFrom, aTo, StandardCopyOption.REPLACE_EXISTING);
        }
    }


    static void writeSidecar(Path aStore, Sidecar aSidecar) throws IOException
    {
        Path file = sidecarOf(aStore);
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(),
                file.getFileName().toString(), ".tmp");
        try
        {
            Files.writeString(temp, Json.write(aSidecar), StandardCharsets.UTF_8);
            moveAtomically(temp, file);
        }
        finally
        {
            deleteQuietly(temp);
        }
    }


    /** Whether a leftover of an interrupted seed lies in {@code aDir}. */
    static boolean hasLeftovers(Path aDir, String aName) throws IOException
    {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(aDir))
        {
            for (Path p : entries)
            {
                if (isLeftover(String.valueOf(p.getFileName()), aName))
                {
                    return true;
                }
            }
        }
        return false;
    }


    private static boolean isLeftover(String aEntry, String aName)
    {
        return aEntry.startsWith(aName + STAGING_INFIX) || aEntry.startsWith(aName + SCRATCH_INFIX)
                || (aEntry.startsWith(aName + SIDECAR_SUFFIX) && aEntry.endsWith(".tmp"));
    }


    /**
     * Leftovers of an interrupted seed (a killed JVM): this store's staging files, scratch
     * directories and sidecar temp files — called only under the seed lock.
     */
    static void sweep(Path aDir, String aName)
    {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(aDir))
        {
            for (Path p : entries)
            {
                String n = String.valueOf(p.getFileName());
                if (!isLeftover(n, aName))
                {
                    continue;
                }
                if (Files.isDirectory(p))
                {
                    EngineStoreSeedSource.deleteTree(p);
                }
                else
                {
                    deleteQuietly(p);
                }
            }
        }
        catch (IOException e)
        {
            LOG.warn("seed leftovers in {} not swept: {}", aDir, e.getMessage());
        }
    }


    private static void deleteQuietly(Path aFile)
    {
        try
        {
            Files.deleteIfExists(aFile);
        }
        catch (IOException e)
        {
            LOG.warn("{} not removed: {}", aFile, e.getMessage());
        }
    }


    /**
     * A message fit for a client and the log: without the API key, and with the store's directory
     * replaced by the file name (security review L8).
     */
    String scrub(String aMessage, Path aTarget)
    {
        String out = aMessage;
        String key = config.getApiKey();
        if (key != null && !key.isBlank())
        {
            out = out.replace(key, "***");
            out = out.replace(key.strip(), "***");
        }
        out = EngineStore.withoutDirectory(out, aTarget);
        Path dir = aTarget.toAbsolutePath().getParent();
        if (dir != null)
        {
            out = out.replace(dir.toString(), "…");
        }
        return out;
    }
}
