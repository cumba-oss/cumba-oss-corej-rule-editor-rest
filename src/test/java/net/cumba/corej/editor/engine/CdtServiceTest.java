package net.cumba.corej.editor.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.editor.error.EditorException;
import net.cumba.corej.editor.testing.RemoteRepo;
import org.junit.jupiter.api.Test;

/** The grid's parse / splice / expectations over the engine's {@code CdtDocument} (§5.6, §12). */
class CdtServiceTest
{

    @Test
    void parseYieldsBlocksColumnsEncodedCellsAndDirectives()
    {
        CdtService.Parsed p = CdtService.parse(RemoteRepo.SCENARIO_B_INVALID_TEXT);
        assertNull(p.error());
        assertEquals(1, p.datasets().size());
        CdtService.Dataset ae = p.datasets().get(0);
        assertEquals("AE", ae.name());
        assertEquals("Adverse Events", ae.label());
        assertEquals(List.of("STUDYID", "USUBJID", "AESEQ", "AETERM", "AEDECOD"),
                ae.columns().stream().map(CdtService.Column::name).toList());
        assertEquals("Num", ae.columns().get(2).type());
        assertEquals("Sequence Number", ae.columns().get(2).label());
        assertEquals(2, ae.rows().size());
        assertEquals("", ae.rows().get(0).get(3), "a quoted empty string is empty text");
        assertEquals("HEADACHE", ae.rows().get(0).get(4));
        assertEquals(List.of("test", "expectViolationCount"),
                p.directives().stream().map(CdtService.Directive::name).toList());
        assertEquals(2, p.directives().get(0).line(), "1-based line of #test");
    }


    @Test
    void missingSentinelsStayBareAndLiteralsThatLookLikeThemAreEscaped()
    {
        String text = """
                #!RuleTest
                #test X expect=noViolation domain=AE
                dataset AE
                col A type=Char
                col B type=Num
                ---
                . | .
                "." | 1
                ---
                """;
        CdtService.Parsed p = CdtService.parse(text);
        assertNull(p.error());
        List<List<String>> rows = p.datasets().get(0).rows();
        assertEquals(".", rows.get(0).get(0), "an unquoted dot is the missing sentinel");
        assertEquals("\\.", rows.get(1).get(0), "a quoted dot is the literal, escaped");
        // a round trip through the splice keeps both meanings
        String spliced = CdtService.splice(text, p.datasets().get(0));
        CdtService.Parsed again = CdtService.parse(spliced);
        assertEquals(rows, again.datasets().get(0).rows());
    }


    @Test
    void spliceRewritesOnlyTheTouchedBlock()
    {
        String text = RemoteRepo.SCENARIO_B_INVALID_TEXT;
        CdtService.Dataset ae = CdtService.parse(text).datasets().get(0);
        List<List<String>> rows = new ArrayList<>(ae.rows());
        rows.add(List.of("STUDY1", "01-002", "3", "", "RASH"));
        CdtService.Dataset edited = new CdtService.Dataset(ae.name(), ae.label(), ae.attrs(),
                ae.fence(), ae.columns(), rows);
        String out = CdtService.splice(text, edited);
        assertTrue(out.startsWith("#!RuleTest\n#test CDISC-CG0270 expect=violation domain=AE\n"
                + "#expectViolationCount 1\n"), "the prelude is untouched: " + out);
        CdtService.Parsed p = CdtService.parse(out);
        assertEquals(3, p.datasets().get(0).rows().size());
        assertEquals("RASH", p.datasets().get(0).rows().get(2).get(4));
    }


    @Test
    void anUnrepresentableCellIsRefusedWithTheEnginesMessage()
    {
        String text = RemoteRepo.SCENARIO_B_INVALID_TEXT;
        CdtService.Dataset ae = CdtService.parse(text).datasets().get(0);
        List<List<String>> rows = List.of(List.of("STUDY1", "01-001", "1", "a\nb", "X"));
        CdtService.Dataset edited = new CdtService.Dataset(ae.name(), ae.label(), ae.attrs(),
                ae.fence(), ae.columns(), rows);
        EditorException e = assertThrows(EditorException.class,
                () -> CdtService.splice(text, edited));
        assertEquals("CDT_UNREPRESENTABLE", e.code());
        assertEquals(400, e.status().value());
        EditorException noBlock = assertThrows(EditorException.class, () -> CdtService.splice(text,
                new CdtService.Dataset("DM", null, Map.of(), ae.fence(), ae.columns(), ae.rows())));
        assertEquals("CDT_UNREPRESENTABLE", noBlock.code());
        EditorException badType = assertThrows(EditorException.class, () -> CdtService.splice(text,
                new CdtService.Dataset("AE", null, Map.of(), ae.fence(), List
                        .of(new CdtService.Column("A", "Blob", null, null, null, null, Map.of())),
                        List.of(List.of("x")))));
        assertEquals("CDT_UNREPRESENTABLE", badType.code());
    }


    @Test
    void expectationsReplaceTheOldLinesAtTheCanonicalPosition()
    {
        String text = RemoteRepo.SCENARIO_B_INVALID_TEXT;
        String out = CdtService.withExpectations(text,
                List.of("#expectViolationCount 2", "#expectViolationAt row=1 AEDECOD=HEADACHE",
                        "#expectViolationAt row=3 AEDECOD=RASH"));
        assertEquals("""
                #!RuleTest
                #test CDISC-CG0270 expect=violation domain=AE
                #expectViolationCount 2
                #expectViolationAt row=1 AEDECOD=HEADACHE
                #expectViolationAt row=3 AEDECOD=RASH
                dataset AE label="Adverse Events"
                """, out.substring(0, out.indexOf("col STUDYID")));
        // before the first #library line when there is one; CRLF kept
        String crlf = "#!RuleTest\r\n#test X expect=violation domain=AE\r\n"
                + "#expectViolationCount 9\r\n#library standard=sdtmig version=3-4\r\n"
                + "dataset AE\r\ncol A type=Char\r\n---\r\nx\r\n---\r\n";
        String out2 = CdtService.withExpectations(crlf, List.of("#expectViolationCount 1"));
        assertEquals("#!RuleTest\r\n#test X expect=violation domain=AE\r\n"
                + "#expectViolationCount 1\r\n#library standard=sdtmig version=3-4\r\n"
                + "dataset AE\r\ncol A type=Char\r\n---\r\nx\r\n---\r\n", out2);
        // removing every expectation
        String none = CdtService.withExpectations(text, List.of());
        assertFalse(none.contains("#expectViolation"));
        EditorException bad = assertThrows(EditorException.class,
                () -> CdtService.withExpectations(text, List.of("#note not an expectation")));
        assertEquals("BAD_REQUEST", bad.code());
    }


    @Test
    void aTextTheParserRefusesReportsTheErrorAndKeepsTheDirectives()
    {
        CdtService.Parsed p = CdtService.parse("#!RuleTest\n#test X expect=valid domain=AE\n"
                + "dataset AE\ncol A type=Char\n---\n1 | 2\n---\n");
        assertNotNull(p.error());
        assertEquals(List.of(), p.datasets());
        assertEquals(1, p.directives().size());
    }
}
