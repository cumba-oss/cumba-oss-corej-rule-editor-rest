package net.cumba.corej.editor.corpus;

import java.util.Locale;

/**
 * Where the split corpus keeps things ({@code cumba-corej-rules}): the check file is the rule (O4,
 * §5.4), the docs sidecar sits beside it under the same family and id (RE-Q3), the scenarios under
 * the test-resources tree keyed by the lower-cased family.
 */
public final class CorpusLayout
{

    public static final String CHECKS = "rules-src/checks";

    public static final String DOCS = "rules-src/docs";

    public static final String SCENARIOS = "src/test/resources/net/cumba/corej/core/ruletestsuites";

    public static final String YAML = ".yaml";

    private CorpusLayout()
    {
    }


    public static String checkPath(String aFamily, String aRuleId)
    {
        return CHECKS + "/" + aFamily + "/" + aRuleId + YAML;
    }


    public static String docsPath(String aFamily, String aRuleId)
    {
        return DOCS + "/" + aFamily + "/" + aRuleId + YAML;
    }


    /**
     * The scenario suite directory of a rule: the family in lower case under the suites tree
     * ({@code …/ruletestsuites/cdisc/CDISC-CG0270}).
     */
    public static String scenarioDir(String aFamily, String aRuleId)
    {
        return SCENARIOS + "/" + aFamily.toLowerCase(Locale.ROOT) + "/" + aRuleId;
    }

    /** The scenario file extension. */
    public static final String CDT = ".cdt";

    /** The rule id of a check file name, or null for anything else. */
    public static boolean isCheckFile(String aName)
    {
        return aName.endsWith(YAML) && aName.length() > YAML.length();
    }


    public static String ruleId(String aCheckFileName)
    {
        return aCheckFileName.substring(0, aCheckFileName.length() - YAML.length());
    }
}
