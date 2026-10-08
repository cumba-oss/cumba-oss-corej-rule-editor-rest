package net.cumba.corej.editor.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.cumba.corej.core.CoreLibraryAccess;
import net.cumba.corej.core.metadata.pickle.HttpArchivePickleSource;
import net.cumba.corej.core.metadata.store.seed.PickleStoreSeeder;
import net.cumba.corej.core.metadata.store.seed.StoreSeedOptions;
import net.cumba.corej.core.metadata.store.seed.StoreSeedReport;
import net.cumba.corej.core.metadata.store.seed.WebApiStoreSeeder;
import net.cumba.corej.editor.config.EditorProperties.SeedType;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The production {@link StoreSeedSource}: the engine's own seeders, exactly as the coreJ CLI's
 * {@code --seed-cache} / {@code --seed-cache-from-api} drive them
 * ({@code CdiscValidate.seedCache}).
 *
 * <ul>
 * <li>{@code GITHUB_PKL}: {@link PickleStoreSeeder} over an {@link HttpArchivePickleSource} of
 * {@link HttpArchivePickleSource#DEFAULT_REPO_URI} at the configured tag, pickles under
 * {@link HttpArchivePickleSource#DEFAULT_REPO_PATH} — no key needed.</li>
 * <li>{@code API}: {@link WebApiStoreSeeder} over {@link CoreLibraryAccess#open} with OUR key,
 * passed programmatically (never through {@code CDISC_API_KEY}, never into the process
 * environment).</li>
 * </ul>
 *
 * <p>
 * ⛔ This is a SEEDER flow — the one place the editor may read the pickle or the web API
 * (databrowser-meta CLAUDE.md, "CDISC Library content comes from OUR metadata store"); everything
 * else reads the store it writes. The scratch it needs (the extracted pickles, ~420 MB; the API's
 * response cache) lives in a hidden directory beside the staging file, i.e. on the store's volume,
 * and is removed afterwards.
 * </p>
 */
@Component
public class EngineStoreSeedSource implements StoreSeedSource
{

    /** {@code WebApiStoreSeeder.fetchProduct}'s failure wording. */
    private static final Pattern FETCH_FAILED = Pattern.compile(": fetching \\S+ failed \\(");

    private final String repoUri;

    private final @Nullable String apiBaseUrl;

    @Autowired
    public EngineStoreSeedSource()
    {
        this(HttpArchivePickleSource.DEFAULT_REPO_URI, null);
    }


    /**
     * @param aRepoUri
     *            the repository the pickles come from
     * @param aApiBaseUrl
     *            the CDISC Library base URL; null = the client's default
     */
    EngineStoreSeedSource(String aRepoUri, @Nullable String aApiBaseUrl)
    {
        repoUri = aRepoUri;
        apiBaseUrl = aApiBaseUrl;
    }


    @Override
    public Outcome seed(Request aRequest, Path aStaging) throws IOException
    {
        StoreSeedOptions options = StoreSeedOptions.of(aStaging).withRefresh(true)
                .withFetchedAt(Instant.now().toString());
        Path dir = aStaging.toAbsolutePath().getParent();
        if (dir == null)
        {
            throw new IOException("the staging file has no directory");
        }
        // named after the store, so StoreSeeding's sweep finds a killed run's leftover (LOW-5)
        Path scratch = Files.createTempDirectory(dir, StoreSeeding.scratchPrefix(aStaging));
        try
        {
            StoreSeedReport report;
            if (aRequest.type() == SeedType.API)
            {
                String key = aRequest.apiKey();
                if (key == null || key.isBlank())
                {
                    throw new IOException("no CDISC Library API key is configured"
                            + " (editor.store.seed.api-key / EDITOR_STORE_SEED_API_KEY)");
                }
                CoreLibraryAccess access = CoreLibraryAccess.open(key, apiBaseUrl, scratch);
                report = new WebApiStoreSeeder(access).seed(options);
            }
            else
            {
                try (HttpArchivePickleSource source = new HttpArchivePickleSource(repoUri,
                        aRequest.tag(), HttpArchivePickleSource.DEFAULT_REPO_PATH, null, scratch))
                {
                    report = new PickleStoreSeeder(source).seed(options);
                }
            }
            return outcome(report.summary(), report.productsSeeded() + report.productsCarried(),
                    report.ctPackagesFetched() + report.ctPackagesCarried(),
                    report.ctPackagesMissed(), report.warnings(), report.sourceRef());
        }
        finally
        {
            deleteTree(scratch);
        }
    }


    /**
     * The outcome of an engine seed, its warnings split into FETCH failures (the source failed to
     * deliver: the store is incomplete, MED-1) and content warnings.
     */
    static Outcome outcome(String aSummary, int aProducts, int aCtPackages, List<String> aMissed,
            List<String> aWarnings, @Nullable String aSourceRef)
    {
        List<String> failures = new ArrayList<>();
        List<String> content = new ArrayList<>();
        for (String w : aWarnings)
        {
            (isFetchFailure(w) ? failures : content).add(w);
        }
        return new Outcome(aSummary, aProducts, aCtPackages, aMissed, failures, content,
                aSourceRef);
    }


    /**
     * Whether a seeder warning reports a FETCH that threw, i.e. a transient failure a later seed
     * may not have — as opposed to a content warning (a footnote marker, an empty projection, a CT
     * package the source does not offer at all). ⚠ The engine reports these only as text, so this
     * matches its two wordings: {@code WebApiStoreSeeder.fetchProduct} ("&lt;key&gt;: fetching
     * &lt;href&gt; failed (…) - skipped") and {@code SeedRun.acquireCtPackages} ("&lt;id&gt;:
     * source failed delivering the CT package (…)"); {@code StoreSeedingTest} pins both wordings.
     */
    static boolean isFetchFailure(String aWarning)
    {
        return aWarning.contains(": source failed delivering the CT package (")
                || FETCH_FAILED.matcher(aWarning).find();
    }


    /** Removes a scratch directory; a leftover is harmless (next seed's sweep), never fatal. */
    static void deleteTree(Path aDir)
    {
        if (!Files.exists(aDir))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(aDir))
        {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(p);
            }
        }
        catch (IOException e)
        {
            StoreSeeding.LOG.warn("seed scratch {} not removed: {}", aDir, e.getMessage());
        }
    }
}
