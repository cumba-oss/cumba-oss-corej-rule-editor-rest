package net.cumba.corej.editor.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.editor.config.EditorProperties.SeedType;
import org.jspecify.annotations.Nullable;

/**
 * Where {@link StoreSeeding} gets a store from: one call writes a complete store to a STAGING file.
 * The production binding ({@link EngineStoreSeedSource}) drives the engine's seeders; a test binds
 * a fake, so no test reaches the network.
 */
@FunctionalInterface
public interface StoreSeedSource
{

    /**
     * What to seed from.
     *
     * @param type
     *            {@code GITHUB_PKL} or {@code API}
     * @param tag
     *            the cdisc-rules-engine tag ({@code GITHUB_PKL}), else null
     * @param apiKey
     *            the CDISC Library API key ({@code API}), else null — ⛔ a secret: never logged
     */
    record Request(SeedType type, @Nullable String tag, @Nullable String apiKey)
    {

        /** Never the key. */
        @Override
        public String toString()
        {
            return "Request[type=" + type + ", tag=" + tag + "]";
        }
    }


    /**
     * What a seed produced.
     *
     * @param summary
     *            the seeder's one-line summary (for the log)
     * @param products
     *            products seeded or carried
     * @param ctPackages
     *            CT packages fetched or carried
     * @param ctPackagesMissed
     *            published CT packages the store will answer ABSENT for (logged)
     * @param fetchFailures
     *            what the source FAILED to deliver (a product or CT package fetch that threw): the
     *            store is then INCOMPLETE — published only when no store is open, recorded as
     *            incomplete so the next start re-seeds
     * @param warnings
     *            the seeder's content warnings, fetch failures excluded (logged; they do not fail
     *            the seed)
     * @param details
     *            the seeder's per-entry detail lines ({@code StoreSeedReport.details()}: a stale
     *            erratum, a seed supplement IGNORED or REPLACING a copy the seed did not carry, a
     *            footnote-marker label) — logged, never a warning; empty on a conforming seed
     * @param sourceRef
     *            where the content came from (e.g. the repository at the tag and its commit), or
     *            null
     */
    record Outcome(String summary, int products, int ctPackages, List<String> ctPackagesMissed,
            List<String> fetchFailures, List<String> warnings, List<String> details,
            @Nullable String sourceRef)
    {

        public Outcome
        {
            ctPackagesMissed = List.copyOf(ctPackagesMissed);
            fetchFailures = List.copyOf(fetchFailures);
            warnings = List.copyOf(warnings);
            details = List.copyOf(details);
        }


        /** A complete outcome: nothing missed, no fetch failed, no detail lines. */
        public Outcome(String aSummary, int aProducts, int aCtPackages, List<String> aWarnings,
                @Nullable String aSourceRef)
        {
            this(aSummary, aProducts, aCtPackages, List.of(), List.of(), aWarnings, List.of(),
                    aSourceRef);
        }


        /** True when no fetch failed. */
        public boolean complete()
        {
            return fetchFailures.isEmpty();
        }
    }

    /**
     * Writes a complete store to {@code aStaging} (which does not exist yet; its directory does).
     *
     * @param aRequest
     *            the source
     * @param aStaging
     *            the file to write
     * @return what was seeded
     * @throws IOException
     *             when the source fails; nothing is published then
     */
    Outcome seed(Request aRequest, Path aStaging) throws IOException;
}
