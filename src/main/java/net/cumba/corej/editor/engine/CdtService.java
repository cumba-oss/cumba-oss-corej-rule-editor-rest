package net.cumba.corej.editor.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.corej.editor.error.EditorException;
import net.cumba.datatable.provider.cdt.CdtColumn;
import net.cumba.datatable.provider.cdt.CdtDataset;
import net.cumba.datatable.provider.cdt.CdtDocument;
import net.cumba.datatable.provider.cdt.CdtParseException;
import net.cumba.datatable.provider.cdt.CdtParser;
import net.cumba.datatable.provider.cdt.CdtType;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * The scenario grid's model on the server (PLAN-rule-editor §5.6, §12): the text is the truth, the
 * grid is a view of the dataset blocks the engine's own {@code CdtParser} reads, and every grid
 * edit is a {@code CdtDocument} splice of the text — the touched block's {@code col} lines and rows
 * are rewritten, nothing else moves. Cells travel in the parser's field encoding (an unquoted
 * missing sentinel bare — {@code .}, {@code ._}, {@code .A}…; a literal that would read as one
 * backslash-escaped; text otherwise), so what the grid shows as missing is what the engine reads as
 * missing.
 *
 * <p>
 * Facts carried over from PACK 2's review: {@code dataset(name)} is the <b>first</b> block of that
 * name (case-insensitive) — so a splice addresses its block by INDEX when the grid sends one (REST
 * review #6: a scenario may hold two blocks of one name, and the second must not overwrite the
 * first); a block written back unchanged is still realigned; comments between a rewritten block's
 * rows are dropped; a cell the parser cannot read back exactly is refused with the engine's message
 * ({@code 400 CDT_UNREPRESENTABLE}) rather than written.
 * </p>
 */
public final class CdtService
{

    private static final Pattern EXPECTATION_LINE = Pattern.compile("^#expectViolation");

    private static final Pattern LIBRARY_LINE = Pattern.compile("^#library\\b");

    private static final Pattern DATASET_LINE = Pattern.compile("^dataset\\b");

    private static final Pattern TEST_LINE = Pattern.compile("^#test\\s");

    private static final Pattern EXPECT_TOKEN = Pattern.compile("(?<=\\s)expect=(\"[^\"]*\"|\\S+)");

    /**
     * Directives only one verdict admits: rewriting {@code expect=} away from it must drop them, or
     * the parser refuses the result ({@code #expect-execution-error} needs {@code executionError},
     * {@code #expect-abort} needs {@code skipped}).
     */
    private static final Pattern VERDICT_BOUND_LINE = Pattern
            .compile("^#(expect-execution-error|expect-abort)(\\s|$)");

    /** The verdicts "take actual as expected" may set (REST review #2). */
    static final List<String> PROPOSABLE_VERDICTS = List.of("violation", "noViolation");

    /** A column as the grid sees it. */
    public record Column(String name, String type, @Nullable String label, @Nullable Integer length,
            @Nullable String format, @Nullable String codelist, Map<String, String> attrs)
    {

        public Column
        {
            attrs = Map.copyOf(attrs);
        }
    }


    /** A dataset block as the grid sees it. */
    public record Dataset(String name, @Nullable String label, Map<String, String> attrs,
            String fence, List<Column> columns, List<List<String>> rows)
    {

        public Dataset
        {
            attrs = Map.copyOf(attrs);
            columns = List.copyOf(columns);
            rows = List.copyOf(rows.stream().map(List::copyOf).toList());
        }
    }


    /** A prelude directive line (1-based). */
    public record Directive(int line, String name, String text)
    {
    }


    /**
     * The parsed document.
     *
     * @param datasets
     *            the blocks in file order (empty when {@code error} is set)
     * @param directives
     *            every {@code #directive} line of the prelude
     * @param error
     *            the parser's message when the text is not a {@code .cdt} it accepts
     */
    public record Parsed(List<Dataset> datasets, List<Directive> directives, @Nullable String error)
    {

        public Parsed
        {
            datasets = List.copyOf(datasets);
            directives = List.copyOf(directives);
        }
    }

    private CdtService()
    {
    }


    public static Parsed parse(String aText)
    {
        List<Directive> directives = directives(aText);
        try
        {
            List<Dataset> datasets = new ArrayList<>();
            for (CdtDataset ds : CdtParser.parseAll(aText, "buffer"))
            {
                datasets.add(toDataset(ds));
            }
            return new Parsed(datasets, directives, null);
        }
        catch (CdtParseException | IllegalArgumentException | IllegalStateException e)
        {
            return new Parsed(List.of(), directives, String.valueOf(e.getMessage()));
        }
    }


    static List<Directive> directives(String aText)
    {
        List<Directive> out = new ArrayList<>();
        List<String> lines = CdtDocument.splitKeepingTerminators(aText);
        for (int i = 0; i < lines.size(); i++)
        {
            String line = lines.get(i).strip();
            if (DATASET_LINE.matcher(line).find())
            {
                break;
            }
            if (line.startsWith("#") && line.length() > 1 && line.charAt(1) != ' '
                    && line.charAt(1) != '!')
            {
                int space = line.indexOf(' ');
                String name = space < 0 ? line.substring(1) : line.substring(1, space);
                out.add(new Directive(i + 1, name, line));
            }
        }
        return out;
    }


    static Dataset toDataset(CdtDataset aDataset)
    {
        List<Column> columns = new ArrayList<>();
        for (CdtColumn c : aDataset.getColumns())
        {
            columns.add(new Column(c.getName(), c.getType().token(), c.getLabel(), c.getLength(),
                    c.getFormat(), c.getCodelist(), c.getAttrs()));
        }
        return new Dataset(aDataset.getName(), aDataset.getLabel(), aDataset.getAttrs(),
                aDataset.getFence(), columns, aDataset.getDataRows());
    }


    /** {@link #splice(String, Dataset, Integer)} by name: the first block of the name. */
    public static String splice(String aText, Dataset aDataset)
    {
        return splice(aText, aDataset, null);
    }


    /**
     * Replace one dataset block with the grid's content.
     *
     * @param aIndex
     *            the block's position in the document (0-based, file order — as {@link #parse}
     *            lists them); {@code null} addresses the first block named {@code aDataset.name()}
     * @throws EditorException
     *             {@code 400 CDT_UNREPRESENTABLE} with the engine's message when the document holds
     *             no such block (or the indexed block has another name), a column has no type, or a
     *             cell / name cannot be written so that the parser reads it back
     */
    public static String splice(String aText, Dataset aDataset, @Nullable Integer aIndex)
    {
        CdtDataset.CdtDatasetBuilder builder = CdtDataset.builder().name(aDataset.name())
                .label(aDataset.label()).fence(aDataset.fence());
        aDataset.attrs().forEach(builder::attr);
        for (Column c : aDataset.columns())
        {
            CdtType type = CdtType.parse(c.type());
            if (type == null)
            {
                throw unrepresentable("column " + c.name() + ": unknown type '" + c.type() + "'");
            }
            CdtColumn.CdtColumnBuilder column = CdtColumn.builder().name(c.name()).type(type)
                    .label(c.label()).length(c.length()).format(c.format()).codelist(c.codelist());
            c.attrs().forEach(column::attr);
            builder.column(column.build());
        }
        for (List<String> row : aDataset.rows())
        {
            builder.dataRow(new ArrayList<>(row));
        }
        try
        {
            CdtDocument document = CdtDocument.parse(aText);
            if (aIndex == null)
            {
                return document.withDataset(builder.build()).text();
            }
            return spliceAt(document, aIndex, builder.build());
        }
        catch (CdtParseException | IllegalArgumentException | IllegalStateException e)
        {
            EditorException refused = unrepresentable(String.valueOf(e.getMessage()));
            refused.initCause(e);
            throw refused;
        }
    }


    /**
     * The block at an index rewritten: the engine's {@code withDataset} addresses by name only (the
     * first of a name), so the block's own lines are spliced as a document of their own and put
     * back in place — every byte outside the block unchanged, as {@code withDataset} guarantees
     * inside one.
     */
    private static String spliceAt(CdtDocument aDocument, int aIndex, CdtDataset aDataset)
    {
        List<CdtDocument.DatasetBlock> blocks = aDocument.datasets();
        if (aIndex < 0 || aIndex >= blocks.size())
        {
            throw new IllegalArgumentException("no dataset block #" + aIndex + " in the document ("
                    + blocks.size() + " block(s))");
        }
        CdtDocument.DatasetBlock block = blocks.get(aIndex);
        if (!block.name().equalsIgnoreCase(aDataset.getName()))
        {
            throw new IllegalArgumentException("dataset block #" + aIndex + " is " + block.name()
                    + ", not " + aDataset.getName());
        }
        List<String> lines = CdtDocument.splitKeepingTerminators(aDocument.text());
        String own = String.join("", lines.subList(block.headerLine(), block.end()));
        String rewritten = CdtDocument.parse(own).withDataset(aDataset).text();
        return String.join("", lines.subList(0, block.headerLine())) + rewritten
                + String.join("", lines.subList(block.end(), lines.size()));
    }


    /**
     * Replace the {@code #expectViolation*} lines with the given ones at the canonical position —
     * before the first {@code #library} directive, else before the first dataset block — keeping
     * the file's line terminator ("take actual as expected", §5.6, written only on the user's
     * confirmation).
     */
    public static String withExpectations(String aText, List<String> aLines)
    {
        return withExpectations(aText, aLines, null);
    }


    /**
     * {@link #withExpectations(String, List)}, and — with {@code aExpect} — the {@code #test}
     * line's {@code expect=} set to it (REST review #2: the proposal of a run that fired on a
     * {@code noViolation} scenario is {@code violation}, of one that fired nothing on a
     * {@code violation} scenario {@code noViolation} with no expectation lines). A directive only
     * the old verdict admits ({@code #expect-execution-error}, {@code #expect-abort}) goes with it,
     * so the result is a text the parser accepts.
     *
     * @param aExpect
     *            {@code violation}, {@code noViolation}, or {@code null} to leave the verdict
     * @throws EditorException
     *             {@code 400 BAD_REQUEST} for a line that is not an expectation, another verdict,
     *             location expectations with {@code noViolation}, or a text without a {@code #test}
     *             line to rewrite
     */
    public static String withExpectations(String aText, List<String> aLines,
            @Nullable String aExpect)
    {
        String nl = aText.indexOf("\r\n") >= 0 ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(CdtDocument.splitKeepingTerminators(aText));
        if (aExpect != null)
        {
            requireProposable(aExpect, aLines);
            int test = testLineIndex(lines);
            if (test < 0)
            {
                throw new EditorException(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                        "the scenario has no #test line whose expect= could be set");
            }
            lines.set(test, rewriteExpect(lines.get(test), aExpect));
            lines.removeIf(l -> VERDICT_BOUND_LINE.matcher(l.strip()).find()
                    && !admits(l.strip(), aExpect));
        }
        lines.removeIf(l -> EXPECTATION_LINE.matcher(l.strip()).find());
        int insertAt = lines.size();
        for (int i = 0; i < lines.size(); i++)
        {
            String s = lines.get(i).strip();
            if (LIBRARY_LINE.matcher(s).find() || DATASET_LINE.matcher(s).find())
            {
                insertAt = i;
                break;
            }
        }
        List<String> inserted = new ArrayList<>();
        for (String l : aLines)
        {
            String line = l.strip();
            if (!EXPECTATION_LINE.matcher(line).find())
            {
                throw new EditorException(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                        "not an expectation line: " + line);
            }
            inserted.add(line + nl);
        }
        if (insertAt > 0 && !endsWithTerminator(lines.get(insertAt - 1)))
        {
            lines.set(insertAt - 1, lines.get(insertAt - 1) + nl);
        }
        lines.addAll(insertAt, inserted);
        return String.join("", lines);
    }


    private static void requireProposable(String aExpect, List<String> aLines)
    {
        if (!PROPOSABLE_VERDICTS.contains(aExpect))
        {
            throw new EditorException(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                    "expect must be one of " + PROPOSABLE_VERDICTS + ", not '" + aExpect + "'");
        }
        if ("noViolation".equals(aExpect))
        {
            for (String l : aLines)
            {
                String line = l.strip();
                boolean nonZeroCount = line.startsWith("#expectViolationCount")
                        && !line.matches("#expectViolationCount\\s+0");
                if (line.startsWith("#expectViolationAt") || nonZeroCount)
                {
                    throw new EditorException(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                            "expect=noViolation admits no violation expectation: " + line);
                }
            }
        }
    }


    /** Whether a verdict-bound directive line survives the verdict. */
    private static boolean admits(String aDirective, String aExpect)
    {
        return aDirective.startsWith("#expect-execution-error") ? "executionError".equals(aExpect)
                : "skipped".equals(aExpect);
    }


    /** The index of the prelude's {@code #test} line, or -1. */
    private static int testLineIndex(List<String> aLines)
    {
        for (int i = 0; i < aLines.size(); i++)
        {
            String s = aLines.get(i).strip();
            if (DATASET_LINE.matcher(s).find())
            {
                return -1;
            }
            if (TEST_LINE.matcher(s).find())
            {
                return i;
            }
        }
        return -1;
    }


    /** The line with its {@code expect=} token set; its whitespace and terminator kept. */
    static String rewriteExpect(String aLine, String aExpect)
    {
        int end = aLine.length();
        while (end > 0 && isLineBreak(aLine.charAt(end - 1)))
        {
            end--;
        }
        String body = aLine.substring(0, end);
        String terminator = aLine.substring(end);
        Matcher m = EXPECT_TOKEN.matcher(body);
        if (m.find())
        {
            body = body.substring(0, m.start()) + "expect=" + aExpect + body.substring(m.end());
        }
        else
        {
            body = body.stripTrailing() + " expect=" + aExpect;
        }
        return body + terminator;
    }


    /**
     * The scenario's {@code #test} line as {@link #withExpectations(String, List, String)} writes
     * it for a verdict, without its terminator; {@code null} when the text has no {@code #test}
     * line (the run report's proposal shows it in the confirm diff).
     */
    public static @Nullable String testLineWith(String aText, String aExpect)
    {
        List<String> lines = CdtDocument.splitKeepingTerminators(aText);
        int test = testLineIndex(lines);
        if (test < 0)
        {
            return null;
        }
        String line = rewriteExpect(lines.get(test), aExpect);
        int end = line.length();
        while (end > 0 && isLineBreak(line.charAt(end - 1)))
        {
            end--;
        }
        return line.substring(0, end);
    }


    private static boolean isLineBreak(char aChar)
    {
        return aChar == 0x0D || aChar == 0x0A || aChar == 0x0B || aChar == 0x0C || aChar == 0x85
                || aChar == 0x2028 || aChar == 0x2029;
    }


    private static boolean endsWithTerminator(String aLine)
    {
        if (aLine.isEmpty())
        {
            return false;
        }
        int c = aLine.charAt(aLine.length() - 1);
        // the \R class CdtParser splits on: CR, LF, VT, FF, NEL, LS, PS
        return c == 0x0D || c == 0x0A || c == 0x0B || c == 0x0C || c == 0x85 || c == 0x2028
                || c == 0x2029;
    }


    static EditorException unrepresentable(String aMessage)
    {
        return new EditorException(HttpStatus.BAD_REQUEST, "CDT_UNREPRESENTABLE", aMessage);
    }


    /** The attrs of a dataset or column as a mutable copy. */
    static Map<String, String> copy(Map<String, String> aAttrs)
    {
        return new LinkedHashMap<>(aAttrs);
    }
}
