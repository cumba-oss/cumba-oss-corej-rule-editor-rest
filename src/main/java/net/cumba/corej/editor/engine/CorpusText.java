package net.cumba.corej.editor.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.cumba.corej.editor.git.CorpusSource;
import org.jspecify.annotations.Nullable;

/**
 * Text files of the corpus the engine integration reads beside the editable trees: the package
 * standards, the language level, the schema, the function reference, the registers. These paths are
 * chosen by the server, never by a request, so they bypass the allow-list (which guards what a user
 * may read and write) and go through the source's own containment only.
 */
public final class CorpusText
{

    /** {@code rules-src/package-standards.json} (PackageStandardsMap.FILE). */
    public static final String PACKAGE_STANDARDS = "rules-src/package-standards.json";

    /** {@code rules-src/LANGUAGE-LEVEL} (PLAN-rule-authoring-api §3.2). */
    public static final String LANGUAGE_LEVEL = "rules-src/LANGUAGE-LEVEL";

    /** The function reference the help text is parsed from (authoring plan P4). */
    public static final String FUNCTIONS_MD = "rule-guide/reference/functions.md";

    /** The internal corpus's authored schema; the OSS corpus keeps it under {@code schema/}. */
    public static final String SCHEMA_DOCUMENTATION = "documentation/rule-check.schema.json";

    public static final String SCHEMA_SCHEMA = "schema/rule-check.schema.json";

    public static final String EQUIVALENCE_REGISTER = "registers/rule-inventory/rule-equivalence.yaml";

    public static final String PARENTAGE_REGISTER = "registers/derivation/minted-rule-parentage.tsv";

    /** The dummy dictionary bundle {@code #dictionaries dummy} loads. */
    public static final String DICTIONARIES_DIR = "src/test/resources/dictionaries";

    private CorpusText()
    {
    }


    public static Optional<String> read(CorpusSource aSource, String aPath) throws IOException
    {
        return aSource.read(aPath).map(b -> new String(b, StandardCharsets.UTF_8));
    }


    /** The file's text, or the empty string when it does not exist. */
    public static String readOrEmpty(CorpusSource aSource, String aPath) throws IOException
    {
        return read(aSource, aPath).orElse("");
    }


    /**
     * The corpus's declared rule-language level, or {@code null} when the branch declares none (a
     * branch predating the level file).
     *
     * @throws NumberFormatException
     *             never — a malformed file reads as {@code null}
     */
    public static @Nullable Integer languageLevel(CorpusSource aSource) throws IOException
    {
        String text = readOrEmpty(aSource, LANGUAGE_LEVEL).strip();
        if (text.isEmpty())
        {
            return null;
        }
        try
        {
            return Integer.valueOf(text);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
