package net.cumba.corej.editor.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;
import net.cumba.corej.core.RuleLanguage;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.StoreFormatException;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.editor.config.EditorProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The engine's metadata store as this instance sees it, opened at startup and shared read-only by
 * the analysis (scope universe, completion, tooltips) and handed by path to every run worker
 * (PLAN-rule-editor §4.2, §5.1.1).
 *
 * <p>
 * ⛔ CDISC Library content comes <b>only</b> from this store (databrowser-meta CLAUDE.md). The file
 * is {@code editor.metadata-store}, else the engine's own order ({@code CDISC_METADATA_STORE}, then
 * {@code -Dcdisc.metadata.store}) — the configured path whether or not the file exists yet, since
 * {@link StoreSeeding} may create it. A store of another format is refused by the engine
 * ({@link StoreFormatException}) and this instance then runs <em>without</em> a store: analysis
 * still works (scope universe empty, {@code storeAvailable=false} on the wire), a scenario needing
 * the store fails with the engine's own reason. Only {@link StoreSeeding} ever writes the file.
 * </p>
 *
 * <p>
 * ⭐ The store can appear mid-run (owner, 2026-10-08): {@link StoreSeeding} seeds it in the
 * background and then {@link #reopen(String) reopens} it. Everything a reader sees is one immutable
 * {@link State} behind a volatile field, so a reader that takes {@link #store()} (or
 * {@link #status()}) once sees a consistent store, reason and format; a reader that cached anything
 * derived from the store keys it by {@link #generation()}, which moves whenever the open store
 * changes. A replaced store is never closed: it is fully in memory and a request may still hold it
 * ({@link MetadataStore#close()} releases nothing today); {@link #close()} closes the current one.
 * </p>
 *
 * <p>
 * {@link #unavailableReason()} is served to every client ({@code /api/info}): it names the store by
 * its file name, never by the server's path (security review L8); the log has the path.
 * </p>
 */
@Component
public final class EngineStore implements AutoCloseable
{

    /** What the configured store file was found to be. */
    public enum Condition
    {
        /** A store of the engine's format is open. */
        OPEN,
        /** No store file is configured. */
        NOT_CONFIGURED,
        /** The configured file does not exist (yet). */
        ABSENT,
        /** A store of an older format: refused, re-seed once. */
        OLDER_FORMAT,
        /** A store written by a newer build: refused, never replaced automatically. */
        NEWER_FORMAT,
        /** Not a readable store. */
        UNREADABLE,
        /** {@link StoreSeeding} is writing it. */
        SEEDING,
        /** {@link StoreSeeding} failed writing it. */
        SEED_FAILED
    }


    /**
     * What {@code /api/info} serves, read from one {@link State}.
     *
     * @param available
     *            a store is open
     * @param formatVersion
     *            the open store's format, -1 without one
     * @param reason
     *            why there is no store; empty when there is one
     * @param seeding
     *            a seed (first seed or re-seed) is running right now
     * @param seedStatus
     *            the state of the last or ongoing seed, independent of the store's availability
     *            ("seeding (API)…", "seed failed (API): …", "incomplete (API): …"); empty when
     *            there is nothing to say (no seed ran, or it completed) — never the key or a server
     *            path
     */
    public record Status(boolean available, int formatVersion, String reason, boolean seeding,
            String seedStatus)
    {
    }


    /**
     * One consistent view: the open store (or null), why there is none, its generation, and the
     * last or ongoing seed's state (LOW-4: visible while an old store serves).
     */
    private record State(@Nullable MetadataStore store, String reason, Condition condition,
            long generation, boolean seeding, String seedStatus)
    {

        State withSeed(boolean aSeeding, String aSeedStatus)
        {
            return new State(store, reason, condition, generation, aSeeding, aSeedStatus);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(EngineStore.class);

    private final @Nullable Path path;

    private volatile State state;

    @Autowired
    public EngineStore(EditorProperties aProperties)
    {
        this(resolve(aProperties.getMetadataStore()));
    }


    public EngineStore(@Nullable Path aPath)
    {
        path = aPath;
        state = open(aPath, 0);
    }


    /** Opens the file; the opened store becomes the state, and {@link #close()} closes it. */
    private static State open(@Nullable Path aPath, long aGeneration)
    {
        MetadataStore opened = null;
        String reason = "";
        Condition condition = Condition.OPEN;
        if (aPath == null)
        {
            reason = "no metadata store configured (editor.metadata-store / "
                    + StoreMetadataProviderFactory.STORE_ENV + ")";
            condition = Condition.NOT_CONFIGURED;
        }
        else if (Files.isDirectory(aPath))
        {
            // LOW-6: never "absent" (a seed would download, then fail to move over a directory)
            reason = "the configured store path is a directory, not a file";
            condition = Condition.UNREADABLE;
            LOG.warn("metadata store path is a directory: {}", aPath);
        }
        else if (Files.exists(aPath) && !Files.isRegularFile(aPath))
        {
            reason = "the configured store path is not a regular file";
            condition = Condition.UNREADABLE;
            LOG.warn("metadata store path is not a regular file: {}", aPath);
        }
        else if (!Files.isRegularFile(aPath))
        {
            reason = "the configured metadata store file does not exist";
            condition = Condition.ABSENT;
            LOG.warn("metadata store not found: {}", aPath);
        }
        else
        {
            try
            {
                opened = MetadataStore.open(aPath);
            }
            catch (StoreFormatException e)
            {
                condition = e.writtenByNewerBuild() ? Condition.NEWER_FORMAT
                        : Condition.OLDER_FORMAT;
                reason = "metadata store refused: " + withoutDirectory(e.describe(), aPath);
            }
            catch (IOException e)
            {
                reason = "the configured metadata store is unreadable";
                condition = Condition.UNREADABLE;
                LOG.warn("metadata store {} unreadable", aPath, e);
            }
        }
        if (opened != null)
        {
            LOG.info("metadata store {} (format {}, {} products, {} CT packages)", aPath,
                    opened.manifest().formatVersion(), opened.productCatalogue().size(),
                    opened.publishedCtPackages().size());
        }
        else
        {
            LOG.warn("engine runs WITHOUT a metadata store: {}", reason);
        }
        return new State(opened, reason, condition, aGeneration, false, "");
    }


    /**
     * A message with the store's absolute path replaced by its file name — the form a client may
     * see (security review L8).
     */
    public static String withoutDirectory(String aMessage, Path aPath)
    {
        Path name = aPath.getFileName();
        return name == null ? aMessage
                : aMessage.replace(aPath.toAbsolutePath().toString(), name.toString())
                        .replace(aPath.toString(), name.toString());
    }


    static @Nullable Path resolve(@Nullable String aConfigured)
    {
        return resolve(aConfigured, System::getenv, System::getProperty);
    }


    /**
     * The configured store file: {@code aConfigured}, else the environment's
     * {@link StoreMetadataProviderFactory#STORE_ENV}, else the system property
     * {@link StoreMetadataProviderFactory#STORE_PROPERTY} — the engine's order, but the path is
     * kept whether or not the file exists (the engine's resolver drops a missing one; a missing
     * store is what the seeding creates).
     */
    static @Nullable Path resolve(@Nullable String aConfigured, UnaryOperator<String> aEnv,
            UnaryOperator<String> aProperties)
    {
        String configured = aConfigured == null ? "" : aConfigured.strip();
        if (configured.isEmpty())
        {
            configured = blankToEmpty(aEnv.apply(StoreMetadataProviderFactory.STORE_ENV));
        }
        if (configured.isEmpty())
        {
            configured = blankToEmpty(
                    aProperties.apply(StoreMetadataProviderFactory.STORE_PROPERTY));
        }
        return configured.isEmpty() ? null : Path.of(configured);
    }


    private static String blankToEmpty(@Nullable String aValue)
    {
        return aValue == null ? "" : aValue.strip();
    }


    /** The store, when one is open. Take it once per request: it may appear mid-run. */
    public Optional<MetadataStore> store()
    {
        return Optional.ofNullable(state.store());
    }


    /** The store file, configured or resolved, whether or not it exists or opened. */
    public Optional<Path> path()
    {
        return Optional.ofNullable(path);
    }


    public boolean available()
    {
        return state.store() != null;
    }


    /** Why there is no store; empty when there is one. */
    public String unavailableReason()
    {
        return state.reason();
    }


    /** What the configured file was found to be (or that it is being seeded). */
    public Condition condition()
    {
        return state.condition();
    }


    /** True when a store exists but its format is not the engine's (re-seed once). */
    public boolean formatMismatch()
    {
        Condition c = state.condition();
        return c == Condition.OLDER_FORMAT || c == Condition.NEWER_FORMAT;
    }


    /** The format version of the open store, for the info endpoint; -1 without one. */
    public int formatVersion()
    {
        MetadataStore open = state.store();
        return open == null ? -1 : open.manifest().formatVersion();
    }


    /** Availability, format and reason read from ONE state (a store may open in between). */
    public Status status()
    {
        State s = state;
        MetadataStore open = s.store();
        return new Status(open != null, open == null ? -1 : open.manifest().formatVersion(),
                s.reason(), s.seeding(), s.seedStatus());
    }


    /** True exactly while a seed (first seed or re-seed) runs. */
    public boolean seeding()
    {
        return state.seeding();
    }


    /** The last or ongoing seed's state; empty when there is nothing to say. */
    public String seedStatus()
    {
        return state.seedStatus();
    }


    /**
     * Moves whenever the open store changes (absent → open, or a re-seed replaced it): a cache of
     * anything derived from the store keys by it, so nothing computed without the store (or from
     * the previous one) sticks.
     */
    public long generation()
    {
        return state.generation();
    }


    /**
     * {@link StoreSeeding} started writing the store: {@link #seeding()} turns true and the seed
     * status says so; without an open store the reason says so too (an open store keeps serving
     * until the new one replaces it).
     */
    synchronized void seeding(String aReason, String aSeedStatus)
    {
        State s = state;
        state = s.store() == null
                ? new State(null, aReason, Condition.SEEDING, s.generation(), true, aSeedStatus)
                : s.withSeed(true, aSeedStatus);
    }


    /**
     * {@link StoreSeeding} failed (or could not start): the seed status says why, open store or
     * not; without an open store the reason says so too.
     */
    synchronized void seedFailed(String aReason, String aSeedStatus)
    {
        State s = state;
        state = s.store() == null
                ? new State(null, aReason, Condition.SEED_FAILED, s.generation(), false,
                        aSeedStatus)
                : s.withSeed(false, aSeedStatus);
    }


    /**
     * {@link StoreSeeding} ended without changing the store's availability (a partial seed not
     * published over an open store, a seed record not written): only the seed status changes.
     */
    synchronized void seedNote(String aSeedStatus)
    {
        state = state.withSeed(false, aSeedStatus);
    }


    /**
     * Opens the file again after {@link StoreSeeding} replaced it, and publishes the result in one
     * step, with the seed's final status. The previous store is not closed (a request may still
     * hold it).
     */
    synchronized void reopen(String aSeedStatus)
    {
        state = open(path, state.generation() + 1).withSeed(false, aSeedStatus);
    }


    /**
     * The engine's rule-language level ({@code RuleLanguage.LEVEL}, PLAN-rule-authoring-api §3.2).
     */
    public static int languageLevel()
    {
        return RuleLanguage.LEVEL;
    }


    @Override
    public void close()
    {
        Optional.ofNullable(state.store()).ifPresent(MetadataStore::close);
    }
}
