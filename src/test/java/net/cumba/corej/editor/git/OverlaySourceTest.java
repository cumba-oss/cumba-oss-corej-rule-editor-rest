package net.cumba.corej.editor.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.cumba.corej.editor.testing.MapSource;
import org.junit.jupiter.api.Test;

/** The changed files over the HEAD snapshot (REST review #5): reads, existence and listings. */
class OverlaySourceTest
{

    static byte[] bytes(String aText)
    {
        return aText.getBytes(StandardCharsets.UTF_8);
    }


    @Test
    void theOverlayReplacesAddsAndDeletes() throws IOException
    {
        MapSource base = new MapSource(Map.of("a/x.yaml", "old", "a/y.yaml", "keep", "a/gone.yaml",
                "bye", "r/b/z.yaml", "z"));
        Map<String, Optional<byte[]>> overlay = new TreeMap<>();
        overlay.put("a/x.yaml", Optional.of(bytes("new!")));
        overlay.put("a/gone.yaml", Optional.empty());
        overlay.put("a/new/deep.yaml", Optional.of(bytes("d")));
        overlay.put("r/c/fresh.yaml", Optional.of(bytes("f")));
        overlay.put("a/.hidden", Optional.of(bytes("h")));
        OverlaySource s = new OverlaySource(base, overlay);
        assertArrayEquals(bytes("new!"), s.read("a/x.yaml").orElseThrow());
        assertArrayEquals(bytes("keep"), s.read("a/y.yaml").orElseThrow());
        assertTrue(s.read("a/gone.yaml").isEmpty());
        assertFalse(s.exists("a/gone.yaml"));
        assertTrue(s.exists("a/new/deep.yaml"));
        assertEquals(List.of(new CorpusSource.TreeEntry("new", true, 0),
                new CorpusSource.TreeEntry("x.yaml", false, 4),
                new CorpusSource.TreeEntry("y.yaml", false, 4)), s.list("a"));
        assertEquals(
                List.of(new CorpusSource.TreeEntry("b", true, 0),
                        new CorpusSource.TreeEntry("c", true, 0)),
                s.list("r"), "a new file brings its directory");
        // the caller's array is never handed out: a write to a read result changes nothing
        s.read("a/x.yaml").orElseThrow()[0] = 'X';
        assertArrayEquals(bytes("new!"), s.read("a/x.yaml").orElseThrow());
    }
}
