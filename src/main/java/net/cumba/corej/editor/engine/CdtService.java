package net.cumba.corej.editor.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * name (case-insensitive); a block written back unchanged is still realigned; comments between a
 * rewritten block's rows are dropped; a cell the parser cannot read back exactly is refused with
 * the engine's message ({@code 400 CDT_UNREPRESENTABLE}) rather than written.
 * </p>
 */
public final class CdtService
{

    private static final Pattern EXPECTATION_LINE = Pattern.compile("^#expectViolation");

    private static final Pattern LIBRARY_LINE = Pattern.compile("^#library\\b");

    private static final Pattern DATASET_LINE = Pattern.compile("^dataset\\b");

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
        catch (CdtParseException | IllegalArgumentException e)
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


    /**
     * Replace one dataset block (named by {@code aDataset.name()}) with the grid's content.
     *
     * @throws EditorException
     *             {@code 400 CDT_UNREPRESENTABLE} with the engine's message when the document holds
     *             no such block, a column has no type, or a cell / name cannot be written so that
     *             the parser reads it back
     */
    public static String splice(String aText, Dataset aDataset)
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
            return CdtDocument.parse(aText).withDataset(builder.build()).text();
        }
        catch (CdtParseException | IllegalArgumentException e)
        {
            EditorException refused = unrepresentable(String.valueOf(e.getMessage()));
            refused.initCause(e);
            throw refused;
        }
    }


    /**
     * Replace the {@code #expectViolation*} lines with the given ones at the canonical position —
     * before the first {@code #library} directive, else before the first dataset block — keeping
     * the file's line terminator ("take actual as expected", §5.6, written only on the user's
     * confirmation).
     */
    public static String withExpectations(String aText, List<String> aLines)
    {
        String nl = aText.indexOf("\r\n") >= 0 ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(CdtDocument.splitKeepingTerminators(aText));
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
