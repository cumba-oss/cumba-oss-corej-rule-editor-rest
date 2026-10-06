package net.cumba.corej.editor.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.corej.editor.corpus.CorpusLayout;
import net.cumba.corej.editor.git.CorpusSource;
import net.cumba.datatable.provider.cdt.CdtDocument;
import net.cumba.datatable.provider.cdt.CdtParseException;
import org.jspecify.annotations.Nullable;

/**
 * The Scenarios tab's rows (PLAN-rule-editor §5.6): the {@code .cdt} files of a rule's suite
 * directory with what their prelude declares — read from the directive lines and the document's
 * dataset blocks ({@code CdtDocument}, the parser's own reading of the names). The verdict of a
 * scenario is never computed here: that is a run.
 */
public final class ScenarioCatalogue
{

    private static final Pattern TEST = Pattern.compile("^#test\\s+(\\S+)(.*)$", Pattern.MULTILINE);

    private static final Pattern KEY_VALUE = Pattern.compile("(\\w+)=(\"[^\"]*\"|\\S+)");

    private static final Pattern COUNT = Pattern.compile("^#expectViolationCount\\s+(\\d+)\\s*$",
            Pattern.MULTILINE);

    private static final Pattern AT = Pattern.compile("^#expectViolationAt\\b", Pattern.MULTILINE);

    /**
     * One scenario file.
     *
     * @param name
     *            the file name
     * @param path
     *            the repository path
     * @param coreId
     *            the {@code #test} rule id, or {@code null}
     * @param expect
     *            the {@code expect=} token, or {@code null}
     * @param domain
     *            the {@code domain=} token, or {@code null}
     * @param expectViolationCount
     *            the {@code #expectViolationCount}, or {@code null}
     * @param expectViolationAtCount
     *            how many {@code #expectViolationAt} lines
     * @param datasets
     *            the dataset block names as the parser reads them
     * @param sidecars
     *            whether the text names a sidecar ({@code #library-include}, {@code #define-xml},
     *            {@code #define-include})
     * @param parseError
     *            the document parser's message when the blocks could not be located, else
     *            {@code null}
     * @param size
     *            the file size in bytes
     */
    public record Scenario(String name, String path, @Nullable String coreId,
            @Nullable String expect, @Nullable String domain,
            @Nullable Integer expectViolationCount, int expectViolationAtCount,
            List<String> datasets, boolean sidecars, @Nullable String parseError, long size)
    {

        public Scenario
        {
            datasets = List.copyOf(datasets);
        }
    }


    /** The suite directory of a rule: its scenarios and the other files beside them. */
    public record Suite(String directory, List<Scenario> scenarios, List<String> otherFiles)
    {

        public Suite
        {
            scenarios = List.copyOf(scenarios);
            otherFiles = List.copyOf(otherFiles);
        }
    }

    private ScenarioCatalogue()
    {
    }


    public static Suite suite(CorpusSource aSource, String aFamily, String aRuleId)
        throws IOException
    {
        String dir = CorpusLayout.scenarioDir(aFamily, aRuleId);
        List<Scenario> scenarios = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (CorpusSource.TreeEntry e : aSource.list(dir))
        {
            if (e.directory())
            {
                continue;
            }
            String path = dir + "/" + e.name();
            if (!e.name().endsWith(CorpusLayout.CDT))
            {
                others.add(e.name());
                continue;
            }
            String text = CorpusText.readOrEmpty(aSource, path);
            scenarios.add(describe(e.name(), path, text, e.size()));
        }
        return new Suite(dir, scenarios, others);
    }


    /** The prelude facts of one scenario text. */
    public static Scenario describe(String aName, String aPath, String aText, long aSize)
    {
        String coreId = null;
        String expect = null;
        String domain = null;
        Matcher test = TEST.matcher(aText);
        if (test.find())
        {
            coreId = test.group(1);
            Matcher kv = KEY_VALUE.matcher(test.group(2));
            while (kv.find())
            {
                String value = kv.group(2);
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2)
                {
                    value = value.substring(1, value.length() - 1);
                }
                switch (kv.group(1).toLowerCase(Locale.ROOT))
                {
                case "expect" -> expect = value;
                case "domain" -> domain = value;
                default ->
                {
                    // other #test tokens are not catalogued
                }
                }
            }
        }
        Integer count = null;
        Matcher c = COUNT.matcher(aText);
        if (c.find())
        {
            try
            {
                count = Integer.valueOf(c.group(1));
            }
            catch (NumberFormatException e)
            {
                // a count past int is no count the engine reads either (REST review #9: was a 500)
                count = null;
            }
        }
        int ats = 0;
        Matcher at = AT.matcher(aText);
        while (at.find())
        {
            ats++;
        }
        List<String> datasets = new ArrayList<>();
        String parseError = null;
        try
        {
            for (CdtDocument.DatasetBlock block : CdtDocument.parse(aText).datasets())
            {
                datasets.add(block.name());
            }
        }
        catch (CdtParseException | IllegalArgumentException | IllegalStateException e)
        {
            parseError = String.valueOf(e.getMessage());
        }
        boolean sidecars = aText.contains("#library-include") || aText.contains("#define-xml")
                || aText.contains("#define-include");
        return new Scenario(aName, aPath, coreId, expect, domain, count, ats, datasets, sidecars,
                parseError, aSize);
    }
}
