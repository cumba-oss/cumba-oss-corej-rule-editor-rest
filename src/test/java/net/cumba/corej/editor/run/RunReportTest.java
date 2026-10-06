package net.cumba.corej.editor.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.editor.engine.Json;
import org.junit.jupiter.api.Test;

/**
 * A finding keeps the engine's order of keys, values and group key through the worker's JSON and
 * back (REST review #3: {@code Map.copyOf} scrambled all three, per JVM, twice), and a {@code null}
 * key or value is no NPE (which turned a run into {@code WORKER_FAILED}).
 */
class RunReportTest
{

    static Map<String, String> ordered(String... aKeyValues)
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < aKeyValues.length; i += 2)
        {
            m.put(aKeyValues[i], aKeyValues[i + 1]);
        }
        return m;
    }


    @Test
    void findingMapsKeepTheirOrderThroughJson()
    {
        // enough entries that a hash order would differ from the insertion order
        List<String> kv = new ArrayList<>();
        for (String name : List.of("USUBJID", "AESEQ", "AETERM", "AEDECOD", "AESTDTC", "AEENDTC",
                "STUDYID", "DOMAIN", "AEBODSYS", "AESER", "AEREL", "AEOUT"))
        {
            kv.add(name);
            kv.add(name.toLowerCase(java.util.Locale.ROOT));
        }
        Map<String, String> m = ordered(kv.toArray(String[]::new));
        RunReport.Finding f = new RunReport.Finding("R", "AE", 1, "01-001", "1", m, m, "ERROR",
                "ROW", m, List.of("AETERM"), "msg");
        assertEquals(List.copyOf(m.keySet()), List.copyOf(f.keys().keySet()));
        RunReport.Finding back = Json.read(Json.write(f), RunReport.Finding.class);
        assertEquals(List.copyOf(m.keySet()), List.copyOf(back.keys().keySet()));
        assertEquals(List.copyOf(m.keySet()), List.copyOf(back.values().keySet()));
        assertEquals(List.copyOf(m.keySet()), List.copyOf(back.groupKey().keySet()));
    }


    @Test
    void nullKeysAreDroppedAndNullValuesKept()
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("AETERM", null);
        m.put(null, "x");
        m.put("AESEQ", "1");
        RunReport.Finding f = new RunReport.Finding(null, null, 1, null, null, m, m, null, "ROW", m,
                new ArrayList<>(java.util.Arrays.asList("A", null)), null);
        assertEquals(List.of("AETERM", "AESEQ"), List.copyOf(f.keys().keySet()));
        assertNull(f.values().get("AETERM"));
        assertEquals(List.of("A"), f.namedColumns());
        RunReport.Finding nulls = new RunReport.Finding(null, null, 1, null, null, null, null, null,
                "ROW", null, null, null);
        assertEquals(Map.of(), nulls.keys());
        assertEquals(List.of(), nulls.namedColumns());
        RunReport.Finding back = Json.read(Json.write(f), RunReport.Finding.class);
        assertEquals(f, back);
    }
}
