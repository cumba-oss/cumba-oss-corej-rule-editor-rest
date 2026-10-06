package net.cumba.corej.editor.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.editor.testing.TestStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The store as the instance opens it: present, absent, unreadable; never seeded. */
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
}
