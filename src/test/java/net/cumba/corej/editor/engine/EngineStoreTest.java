package net.cumba.corej.editor.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.testing.TestStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The store as the instance opens it: present, absent, unreadable (seeding: StoreSeedingTest). */
class EngineStoreTest
{

    @Test
    void theFixtureStoreOpensAtTheEnginesFormat() throws IOException
    {
        try (EngineStore s = new EngineStore(TestStore.file()))
        {
            assertTrue(s.available());
            assertEquals("", s.unavailableReason());
            assertFalse(s.formatMismatch());
            assertEquals(5, s.formatVersion());
            assertEquals(List.of(TestStore.CT_PACKAGE),
                    s.store().orElseThrow().publishedCtPackages());
            assertEquals(TestStore.file(), s.path().orElseThrow());
            assertTrue(EngineStore.languageLevel() >= 1);
        }
    }


    @Test
    void noPathMissingFileAndGarbageAreUnavailableWithAReason(@TempDir Path aDir) throws IOException
    {
        try (EngineStore none = new EngineStore((Path) null))
        {
            assertFalse(none.available());
            assertTrue(none.unavailableReason().contains("no metadata store configured"));
            assertEquals(-1, none.formatVersion());
            assertTrue(none.path().isEmpty());
        }
        try (EngineStore missing = new EngineStore(aDir.resolve("nope.zip")))
        {
            assertFalse(missing.available());
            assertTrue(missing.unavailableReason().contains("does not exist"),
                    missing.unavailableReason());
            // security review L8: /api/info serves this text to everybody — no server path
            assertFalse(missing.unavailableReason().contains(aDir.toString()),
                    missing.unavailableReason());
        }
        assertEquals("metadata store nope.zip is format 4",
                EngineStore.withoutDirectory(
                        "metadata store " + aDir.resolve("nope.zip") + " is format 4",
                        aDir.resolve("nope.zip")));
        Path garbage = aDir.resolve("garbage.zip");
        Files.writeString(garbage, "not a zip");
        try (EngineStore bad = new EngineStore(garbage))
        {
            assertFalse(bad.available());
            assertFalse(bad.unavailableReason().isEmpty());
            assertFalse(bad.unavailableReason().contains(aDir.toString()), bad.unavailableReason());
        }
        assertNull(EngineStore.resolve(" "), "blank defers to the engine's own resolution,"
                + " which finds nothing in this test JVM");
    }


    @Test
    void theConfiguredPathCountsWhetherOrNotTheFileExists(@TempDir Path aDir)
    {
        assertEquals(Path.of("explicit.zip"),
                EngineStore.resolve(" explicit.zip ", k -> "env.zip", k -> "prop.zip"));
        assertEquals(Path.of("env.zip"), EngineStore.resolve(" ",
                k -> "CDISC_METADATA_STORE".equals(k) ? "env.zip" : null, k -> "prop.zip"));
        assertEquals(Path.of("prop.zip"), EngineStore.resolve(null, k -> " ",
                k -> "cdisc.metadata.store".equals(k) ? "prop.zip" : null));
        assertNull(EngineStore.resolve(null, k -> null, k -> null));
        EditorProperties p = new EditorProperties();
        p.setMetadataStore(aDir.resolve("later.zip").toString());
        try (EngineStore later = new EngineStore(p))
        {
            assertEquals(aDir.resolve("later.zip"), later.path().orElseThrow(),
                    "a missing file is still the seeding target");
            assertEquals(EngineStore.Condition.ABSENT, later.condition());
            assertEquals(
                    new EngineStore.Status(false, -1,
                            "the configured metadata store file does not exist", false, ""),
                    later.status());
            assertEquals(0, later.generation());
        }
    }


    /** LOW-6: a directory at the store path is UNREADABLE (left alone), never ABSENT (seeded). */
    @Test
    void aDirectoryAtTheStorePathIsUnreadableNeverAbsent(@TempDir Path aDir) throws IOException
    {
        Path dir = Files.createDirectories(aDir.resolve("metadata-store.zip"));
        try (EngineStore store = new EngineStore(dir))
        {
            assertEquals(EngineStore.Condition.UNREADABLE, store.condition());
            assertFalse(store.available());
            assertEquals("the configured store path is a directory, not a file",
                    store.unavailableReason());
        }
    }
}
