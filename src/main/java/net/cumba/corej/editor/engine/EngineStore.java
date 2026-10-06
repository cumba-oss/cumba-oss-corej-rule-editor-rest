package net.cumba.corej.editor.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
 * The engine's metadata store as this instance sees it, opened once at startup and shared read-only
 * by the analysis (scope universe, completion, tooltips) and handed by path to every run worker
 * (PLAN-rule-editor §4.2, §5.1.1).
 *
 * <p>
 * ⛔ CDISC Library content comes <b>only</b> from this store (databrowser-meta CLAUDE.md): the
 * editor never seeds, never reads the pickle or the web API. The file is
 * {@code editor.metadata-store}, else the engine's own resolution ({@code CDISC_METADATA_STORE},
 * then {@code -Dcdisc.metadata.store}). A store of another format is refused by the engine
 * ({@link StoreFormatException}) and this instance then runs <em>without</em> a store: analysis
 * still works (scope universe empty, {@code storeAvailable=false} on the wire), a scenario needing
 * the store fails with the engine's own reason. Nothing replaces or rewrites the file.
 * </p>
 */
@Component
public final class EngineStore implements AutoCloseable
{

    private static final Logger LOG = LoggerFactory.getLogger(EngineStore.class);

    private final @Nullable Path path;

    private final @Nullable MetadataStore store;

    private final String unavailableReason;

    private final boolean formatMismatch;

    @Autowired
    public EngineStore(EditorProperties aProperties)
    {
        this(resolve(aProperties.getMetadataStore()));
    }


    @SuppressWarnings("PMD.CloseResource") // the opened store becomes the field; close() closes it
    public EngineStore(@Nullable Path aPath)
    {
        path = aPath;
        MetadataStore opened = null;
        String reason = "";
        boolean mismatch = false;
        if (aPath == null)
        {
            reason = "no metadata store configured (editor.metadata-store / "
                    + StoreMetadataProviderFactory.STORE_ENV + ")";
        }
        else if (!Files.isRegularFile(aPath))
        {
            reason = "metadata store not found: " + aPath;
        }
        else
        {
            try
            {
                opened = MetadataStore.open(aPath);
            }
            catch (StoreFormatException e)
            {
                mismatch = true;
                reason = "metadata store refused: " + e.getMessage();
            }
            catch (IOException e)
            {
                reason = "metadata store unreadable: " + e.getMessage();
            }
        }
        store = opened;
        unavailableReason = reason;
        formatMismatch = mismatch;
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
    }


    static @Nullable Path resolve(@Nullable String aConfigured)
    {
        String configured = aConfigured == null ? "" : aConfigured.strip();
        return StoreMetadataProviderFactory
                .resolveConfiguredFile(configured.isEmpty() ? null : configured);
    }


    /** The store, when one is open. */
    public Optional<MetadataStore> store()
    {
        return Optional.ofNullable(store);
    }


    /** The store file, configured or resolved, whether or not it opened. */
    public Optional<Path> path()
    {
        return Optional.ofNullable(path);
    }


    public boolean available()
    {
        return store != null;
    }


    /** Why there is no store; empty when there is one. */
    public String unavailableReason()
    {
        return unavailableReason;
    }


    /** True when a store exists but its format is not the engine's (re-seed once). */
    public boolean formatMismatch()
    {
        return formatMismatch;
    }


    /** The format version the engine expects, for the info endpoint. */
    public int formatVersion()
    {
        return store == null ? -1 : store.manifest().formatVersion();
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
        if (store != null)
        {
            store.close();
        }
    }
}
