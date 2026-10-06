package net.cumba.corej.editor.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.editor.git.CorpusSource;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The search dialog's free-text arm (§5.3): every rule whose check YAML or docs YAML contains every
 * query term, case-insensitively. Whitespace-separated words are AND-combined substrings; a run in
 * {@code "…"} or {@code '…'} is one phrase (an unterminated quote runs to the end). A hit names the
 * rule, its files, the rule's {@code Description} and up to {@value #MAX_SNIPPETS} matching lines.
 * The semantic arms (mentions variable, scope, function, no scenarios) need the authoring API and
 * arrive in phase 3.
 */
public final class RuleSearch
{

    /** Snippet lines per hit. */
    public static final int MAX_SNIPPETS = 5;

    /** Hard cap on hits. */
    public static final int MAX_LIMIT = 1000;

    /** One query term. */
    public record Term(String text, boolean phrase)
    {
    }


    /** A matching line. */
    public record Snippet(String file, int line, String text)
    {
    }


    /** One hit. */
    public record Hit(String id, String family, String path, @Nullable String docsPath,
            String description, List<Snippet> snippets)
    {

        public Hit
        {
            snippets = List.copyOf(snippets);
        }
    }


    /** The result. */
    public record Result(String query, List<Hit> hits, int total, boolean truncated)
    {

        public Result
        {
            hits = List.copyOf(hits);
        }
    }

    private RuleSearch()
    {
    }


    /** Parses a query into AND-combined terms (empty for a blank query). */
    public static List<Term> parse(String aQuery)
    {
        List<Term> terms = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int i = 0;
        int n = aQuery.length();
        while (i < n)
        {
            char c = aQuery.charAt(i);
            if (c == '"' || c == '\'')
            {
                flush(word, terms);
                int close = aQuery.indexOf(c, i + 1);
                if (close < 0)
                {
                    addPhrase(terms, aQuery.substring(i + 1));
                    return terms;
                }
                addPhrase(terms, aQuery.substring(i + 1, close));
                i = close + 1;
            }
            else if (Character.isWhitespace(c))
            {
                flush(word, terms);
                i++;
            }
            else
            {
                word.append(c);
                i++;
            }
        }
        flush(word, terms);
        return terms;
    }


    private static void flush(StringBuilder aWord, List<Term> aTerms)
    {
        if (aWord.length() > 0)
        {
            aTerms.add(new Term(aWord.toString().toLowerCase(Locale.ROOT), false));
            aWord.setLength(0);
        }
    }


    private static void addPhrase(List<Term> aTerms, String aPhrase)
    {
        if (!aPhrase.isBlank())
        {
            aTerms.add(new Term(aPhrase.toLowerCase(Locale.ROOT), true));
        }
    }


    /** Searches every family; {@code aLimit} caps the hits (1..{@value #MAX_LIMIT}). */
    public static Result search(CorpusSource aSource, String aQuery, int aLimit) throws IOException
    {
        List<Term> terms = parse(aQuery);
        int limit = Math.max(1, Math.min(aLimit, MAX_LIMIT));
        List<Hit> hits = new ArrayList<>();
        int total = 0;
        if (terms.isEmpty())
        {
            return new Result(aQuery, hits, 0, false);
        }
        for (CorpusSource.TreeEntry familyDir : aSource.list(CorpusLayout.CHECKS))
        {
            if (!familyDir.directory())
            {
                continue;
            }
            String family = familyDir.name();
            for (CorpusSource.TreeEntry file : aSource.list(CorpusLayout.CHECKS + "/" + family))
            {
                if (file.directory() || !CorpusLayout.isCheckFile(file.name()))
                {
                    continue;
                }
                String id = CorpusLayout.ruleId(file.name());
                String checkPath = CorpusLayout.checkPath(family, id);
                String docsPath = CorpusLayout.docsPath(family, id);
                String check = text(aSource, checkPath);
                String docs = text(aSource, docsPath);
                boolean hasDocs = !docs.isEmpty() || aSource.exists(docsPath);
                String blob = (id + "\n" + check + "\n" + docs).toLowerCase(Locale.ROOT);
                if (!matches(blob, terms))
                {
                    continue;
                }
                total++;
                if (hits.size() < limit)
                {
                    List<Snippet> snippets = new ArrayList<>();
                    snippets(snippets, "check", check, terms);
                    snippets(snippets, "docs", docs, terms);
                    hits.add(new Hit(id, family, checkPath, hasDocs ? docsPath : null,
                            description(check), snippets));
                }
            }
        }
        return new Result(aQuery, hits, total, total > hits.size());
    }


    private static String text(CorpusSource aSource, String aPath) throws IOException
    {
        return aSource.read(aPath).map(b -> new String(b, StandardCharsets.UTF_8)).orElse("");
    }


    static boolean matches(String aBlobLower, List<Term> aTerms)
    {
        for (Term t : aTerms)
        {
            if (!aBlobLower.contains(t.text()))
            {
                return false;
            }
        }
        return true;
    }


    static void snippets(List<Snippet> aOut, String aFile, String aText, List<Term> aTerms)
    {
        if (aText.isEmpty())
        {
            return;
        }
        Set<Integer> seen = new LinkedHashSet<>();
        String[] lines = aText.split("\n", -1);
        for (Term t : aTerms)
        {
            for (int i = 0; i < lines.length && aOut.size() < MAX_SNIPPETS; i++)
            {
                if (seen.contains(i))
                {
                    continue;
                }
                if (lines[i].toLowerCase(Locale.ROOT).contains(t.text()))
                {
                    seen.add(i);
                    aOut.add(new Snippet(aFile, i + 1, lines[i].strip()));
                    break;
                }
            }
        }
    }


    /** The rule's {@code Description}, {@code ""} when the YAML does not parse or has none. */
    static String description(String aCheckYaml)
    {
        if (aCheckYaml.isEmpty())
        {
            return "";
        }
        try
        {
            Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(aCheckYaml);
            if (root instanceof Map<?, ?> map)
            {
                Object d = map.get("Description");
                return d == null ? "" : d.toString().strip();
            }
        }
        catch (RuntimeException e)
        {
            // an unparsable buffer is still searchable; the description is just absent
            return "";
        }
        return "";
    }
}
