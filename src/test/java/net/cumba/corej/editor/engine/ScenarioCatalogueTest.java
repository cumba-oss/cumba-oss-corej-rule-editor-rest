package net.cumba.corej.editor.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import net.cumba.corej.editor.corpus.CorpusLayout;
import net.cumba.corej.editor.testing.MapSource;
import net.cumba.corej.editor.testing.RemoteRepo;
import org.junit.jupiter.api.Test;

/** The Scenarios tab's rows (§5.6). */
class ScenarioCatalogueTest
{

    @Test
    void theSuiteListsScenariosWithTheirPreludeFactsAndTheOtherFiles() throws IOException
    {
        MapSource source = new MapSource(RemoteRepo.INITIAL_FILES);
        String dir = CorpusLayout.scenarioDir("CDISC", RemoteRepo.RULE_B);
        source.put(dir + "/library.json", "{}");
        ScenarioCatalogue.Suite suite = ScenarioCatalogue.suite(source, "CDISC", RemoteRepo.RULE_B);
        assertEquals(dir, suite.directory());
        assertEquals(List.of("library.json"), suite.otherFiles());
        assertEquals(2, suite.scenarios().size());
        ScenarioCatalogue.Scenario invalid = suite.scenarios().get(0);
        assertEquals(RemoteRepo.RULE_B + "-invalid-AE.cdt", invalid.name());
        assertEquals(RemoteRepo.SCENARIO_B_INVALID, invalid.path());
        assertEquals(RemoteRepo.RULE_B, invalid.coreId());
        assertEquals("violation", invalid.expect());
        assertEquals("AE", invalid.domain());
        assertEquals(1, invalid.expectViolationCount());
        assertEquals(0, invalid.expectViolationAtCount());
        assertEquals(List.of("AE"), invalid.datasets());
        assertFalse(invalid.sidecars());
        assertNull(invalid.parseError());
        assertTrue(invalid.size() > 0);
        ScenarioCatalogue.Scenario valid = suite.scenarios().get(1);
        assertEquals("noViolation", valid.expect());
        assertEquals(0, valid.expectViolationCount());
        assertEquals(List.of(), ScenarioCatalogue.suite(source, "FDA", "FDA-SD1142").scenarios(),
                "a rule without a suite directory lists nothing");
    }


    @Test
    void describeReadsQuotedTokensSidecarsAndParseErrors()
    {
        ScenarioCatalogue.Scenario s = ScenarioCatalogue.describe("x.cdt", "p/x.cdt", """
                #!RuleTest
                #test FDA-SD1142 expect=violation domain="AE" note="quoted"
                #library-include library.json
                #expectViolationAt row=1 A=1
                #expectViolationAt row=2 A=2
                dataset AE
                col A type=Char
                ---
                1 | 2
                ---
                """, 10);
        assertEquals("AE", s.domain());
        assertEquals("violation", s.expect());
        assertTrue(s.sidecars());
        assertEquals(2, s.expectViolationAtCount());
        assertNull(s.expectViolationCount());
        assertNotNull(s.parseError(), "a row with more fields than columns is the parser's error");
        assertEquals(List.of(), s.datasets());
        ScenarioCatalogue.Scenario empty = ScenarioCatalogue.describe("e.cdt", "p/e.cdt", "", 0);
        assertNull(empty.coreId());
        assertNull(empty.expect());
    }


    @Test
    void anOverflowingCountIsNoCountNotAServerError()
    {
        // REST review #9: Integer.valueOf overflowed into a 500 without a code
        ScenarioCatalogue.Scenario s = ScenarioCatalogue.describe("x.cdt", "p/x.cdt", """
                #!RuleTest
                #test X expect=violation domain=AE
                #expectViolationCount 99999999999999999999
                dataset AE
                col A type=Char
                ---
                1
                ---
                """, 10);
        assertNull(s.expectViolationCount());
        assertEquals(List.of("AE"), s.datasets());
    }
}
