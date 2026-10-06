package net.cumba.corej.editor.corpus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.corej.authoring.AssembledRule;
import net.cumba.corej.authoring.AuthoredRuleAssembler;
import net.cumba.corej.authoring.AuthoredRuleAssembler.StandardVersion;
import net.cumba.corej.authoring.CorpusIndex;
import net.cumba.corej.authoring.RuleInsight;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.editor.engine.CorpusText;
import net.cumba.corej.editor.engine.EngineStore;
import net.cumba.corej.editor.engine.LruCache;
import net.cumba.corej.editor.engine.PackageStandards;
import net.cumba.corej.editor.git.ChangedFile;
import net.cumba.corej.editor.git.CorpusSource;
import net.cumba.corej.editor.web.Access;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The semantic search index of a branch (PLAN-rule-editor §5.3, the search dialog): every check of
 * the view assembled once through the engine ({@code AuthoredRuleAssembler} + {@code RuleInsight} —
 * the same reading the analysis gives one buffer), summarised to what the filters ask: the
 * variables a rule names ({@code --} forms kept), the functions it calls, its scope axes, status,
 * standards and whether it has scenarios. Also the {@code CorpusIndex} the related-rules view reads
 * (§5.5).
 *
 * <p>
 * Cached per view: the key is the HEAD plus the content hash of every uncommitted check / docs
 * file, so a save on the working tree or a branch switch rebuilds it and a reread of the same tree
 * is a hit. A whole corpus assembles in a few seconds (measured 2026-10-06: 3 461 rules in 1.7–3.2
 * s), built synchronously on first use under the workspace lock the controller holds.
 * </p>
 */
@Component
public class CorpusAnalysisIndex
{

    private static final Logger LOG = LoggerFactory.getLogger(CorpusAnalysisIndex.class);

    private static final int MAX_CACHED = 4;

    private static final Pattern CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    /** The search filters beside the free text (every one optional, all ANDed). */
    public record Filter(@Nullable String variable, @Nullable String scope,
            @Nullable String function, @Nullable String status, @Nullable String standard,
            @Nullable String version, boolean noScenarios)
    {

        public boolean isEmpty()
        {
            return blank(variable) && blank(scope) && blank(function) && blank(status)
                    && blank(standard) && blank(version) && !noScenarios;
        }


        static boolean blank(@Nullable String aValue)
        {
            return aValue == null || aValue.isBlank();
        }
    }


    /** One rule as the index knows it. */
    public record RuleSummary(String id, String family, String path, @Nullable String docsPath,
            String status, String description, List<StandardVersion> standards, Set<String> domains,
            Set<String> classes, boolean allDomains, Set<String> referenced, Set<String> functions,
            boolean hasScenarios, boolean parked, boolean loaded, String checkText, String docsText,
            @Nullable JsonNode check)
    {

        public RuleSummary
        {
            standards = List.copyOf(standards);
            domains = Set.copyOf(domains);
            classes = Set.copyOf(classes);
            referenced = Set.copyOf(referenced);
            functions = Set.copyOf(functions);
        }
    }


    /** The index of one view. */
    public static final class Index
    {

        private final Map<String, RuleSummary> rules;

        private final CorpusIndex corpusIndex;

        private final Map<String, Map<String, String>> domainClasses;

        Index(Map<String, RuleSummary> aRules, CorpusIndex aCorpusIndex,
                Map<String, Map<String, String>> aDomainClasses)
        {
            rules = Collections.unmodifiableMap(new LinkedHashMap<>(aRules));
            corpusIndex = aCorpusIndex;
            domainClasses = Map.copyOf(aDomainClasses);
        }


        public Map<String, RuleSummary> rules()
        {
            return rules;
        }


        public CorpusIndex corpusIndex()
        {
            return corpusIndex;
        }


        /** Whether the rule's scope admits a domain: ALL, its explicit domains, or its class. */
        boolean admits(RuleSummary aRule, String aDomain)
        {
            return aRule.allDomains() || aRule.domains().contains(aDomain)
                    || classAdmits(aRule, aDomain);
        }


        /** Whether one of the rule's scope classes is the domain's class in one of its products. */
        boolean classAdmits(RuleSummary aRule, String aDomain)
        {
            for (StandardVersion sv : aRule.standards())
            {
                for (String key : RuleInsight.productKeysOf(List.of(sv)))
                {
                    Map<String, String> classes = domainClasses.get(key);
                    String cls = classes == null ? null : classes.get(aDomain);
                    if (cls != null && aRule.classes().contains(cls))
                    {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private final EngineStore store;

    private final PackageStandards standards;

    private final Set<String> functionNames;

    private final Map<String, Index> cache = new LruCache<>(MAX_CACHED);

    public CorpusAnalysisIndex(EngineStore aStore, PackageStandards aStandards)
    {
        store = aStore;
        standards = aStandards;
        Set<String> names = new LinkedHashSet<>();
        for (FunctionDescriptor d : FunctionRegistry.all())
        {
            names.add(d.name());
        }
        functionNames = Set.copyOf(names);
    }


    /** The index of the view, built on first use. */
    public Index of(Access.View aView) throws IOException
    {
        String key = key(aView);
        synchronized (cache)
        {
            Index cached = cache.get(key);
            if (cached != null)
            {
                return cached;
            }
        }
        long t0 = System.nanoTime();
        Index built = build(aView.source());
        LOG.info("indexed {} rules of {}@{} in {} ms", built.rules().size(), aView.branch(),
                aView.head(), (System.nanoTime() - t0) / 1_000_000);
        synchronized (cache)
        {
            cache.put(key, built);
        }
        return built;
    }


    static String key(Access.View aView) throws IOException
    {
        StringBuilder sb = new StringBuilder(aView.head());
        for (Map.Entry<String, ChangedFile.Kind> change : new TreeMap<>(aView.changes()).entrySet())
        {
            String path = change.getKey();
            if (!path.startsWith(CorpusLayout.CHECKS + "/")
                    && !path.startsWith(CorpusLayout.DOCS + "/")
                    && !path.startsWith(CorpusLayout.SCENARIOS + "/"))
            {
                continue;
            }
            sb.append('|').append(path).append(':').append(change.getValue());
            Optional<byte[]> content = aView.source().read(path);
            sb.append(':').append(content.map(CorpusAnalysisIndex::sha1).orElse("-"));
        }
        return sb.toString();
    }


    static String sha1(byte[] aBytes)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(aBytes));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }


    Index build(CorpusSource aSource) throws IOException
    {
        Map<String, RuleSummary> rules = new TreeMap<>();
        Map<String, JsonNode> checks = new HashMap<>();
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
                RuleSummary summary = summarise(aSource, family, id);
                rules.put(id, summary);
                if (summary.check() != null)
                {
                    checks.put(id, summary.check());
                }
            }
        }
        CorpusIndex corpusIndex = CorpusIndex.of(checks);
        String equivalence = CorpusText.readOrEmpty(aSource, CorpusText.EQUIVALENCE_REGISTER);
        if (!equivalence.isBlank())
        {
            try
            {
                corpusIndex = corpusIndex.withEquivalenceRegister(equivalence);
            }
            catch (JsonProcessingException e)
            {
                LOG.warn("equivalence register unreadable, related rules without it: {}",
                        e.getOriginalMessage());
            }
        }
        String parentage = CorpusText.readOrEmpty(aSource, CorpusText.PARENTAGE_REGISTER);
        if (!parentage.isBlank())
        {
            corpusIndex = corpusIndex.withParentage(parentage);
        }
        return new Index(rules, corpusIndex, domainClasses(rules.values()));
    }


    private RuleSummary summarise(CorpusSource aSource, String aFamily, String aId)
        throws IOException
    {
        String checkPath = CorpusLayout.checkPath(aFamily, aId);
        String docsPath = CorpusLayout.docsPath(aFamily, aId);
        String checkText = CorpusText.readOrEmpty(aSource, checkPath);
        String docsText = CorpusText.read(aSource, docsPath).orElse(null);
        JsonNode check = readCheck(checkText);
        AssembledRule assembled = AuthoredRuleAssembler.assemble(checkText, docsText,
                check == null ? List.of() : standards.forCheck(aSource, check));
        Set<String> referenced = new LinkedHashSet<>();
        if (assembled.rule() != null)
        {
            for (RuleInsight.VariableRef r : RuleInsight.referencedVariables(assembled.rule()))
            {
                referenced.add(r.name().toUpperCase(Locale.ROOT));
            }
        }
        Set<String> domains = new LinkedHashSet<>();
        Set<String> classes = new LinkedHashSet<>();
        boolean all = false;
        if (check != null)
        {
            boolean allDomains = includes(check, "Domains", domains);
            boolean allDatasets = includes(check, "Datasets", domains);
            all = allDomains || allDatasets;
            includes(check, "Classes", classes);
        }
        boolean hasScenarios = false;
        for (CorpusSource.TreeEntry e : aSource.list(CorpusLayout.scenarioDir(aFamily, aId)))
        {
            if (!e.directory() && e.name().endsWith(CorpusLayout.CDT))
            {
                hasScenarios = true;
                break;
            }
        }
        String status = check == null ? "" : check.path("Core").path("Status").asText("");
        return new RuleSummary(aId, aFamily, checkPath, docsText == null ? null : docsPath, status,
                RuleSearch.description(checkText),
                check == null ? List.of() : AuthoredRuleAssembler.standardsOf(check), domains,
                classes, all, referenced, functionsOf(check), hasScenarios, assembled.parked(),
                assembled.rule() != null, checkText, docsText == null ? "" : docsText, check);
    }


    /** The upper-cased {@code Scope.<axis>.Include} entries; true when one of them is ALL. */
    private static boolean includes(JsonNode aCheck, String aAxis, Set<String> aOut)
    {
        boolean all = false;
        JsonNode include = aCheck.path("Scope").path(aAxis).path("Include");
        if (include.isArray())
        {
            for (JsonNode n : include)
            {
                String v = n.asText("").strip().toUpperCase(Locale.ROOT);
                if ("ALL".equals(v))
                {
                    all = true;
                }
                else if (!v.isEmpty())
                {
                    aOut.add(v);
                }
            }
        }
        else if (include.isTextual())
        {
            String v = include.asText().strip().toUpperCase(Locale.ROOT);
            if ("ALL".equals(v))
            {
                all = true;
            }
            else if (!v.isEmpty())
            {
                aOut.add(v);
            }
        }
        return all;
    }


    /** Every registry function named as a call anywhere in the check's text leaves. */
    Set<String> functionsOf(@Nullable JsonNode aCheck)
    {
        Set<String> out = new LinkedHashSet<>();
        if (aCheck != null)
        {
            collectCalls(aCheck, out);
        }
        return out;
    }


    private void collectCalls(JsonNode aNode, Set<String> aOut)
    {
        if (aNode.isTextual())
        {
            Matcher m = CALL.matcher(aNode.asText());
            while (m.find())
            {
                String name = m.group(1);
                if (functionNames.contains(name))
                {
                    aOut.add(name);
                }
            }
        }
        else if (aNode.isContainerNode())
        {
            for (JsonNode child : aNode)
            {
                collectCalls(child, aOut);
            }
        }
    }


    @SuppressWarnings("PMD.CloseResource") // the store is shared, owned and closed by EngineStore
    private Map<String, Map<String, String>> domainClasses(Iterable<RuleSummary> aRules)
    {
        Map<String, Map<String, String>> out = new HashMap<>();
        MetadataStore open = store.store().orElse(null);
        if (open == null)
        {
            return out;
        }
        for (RuleSummary r : aRules)
        {
            for (String key : RuleInsight.productKeysOf(r.standards()))
            {
                out.computeIfAbsent(key, k ->
                {
                    Map<String, String> classes = new HashMap<>();
                    StoredProduct product = open.product(k).orElse(null);
                    if (product != null)
                    {
                        for (StoredClass c : product.classes())
                        {
                            for (StoredDataset ds : c.datasets())
                            {
                                if (ds.name() != null && c.name() != null)
                                {
                                    classes.putIfAbsent(ds.name().toUpperCase(Locale.ROOT),
                                            c.name().toUpperCase(Locale.ROOT));
                                }
                            }
                        }
                    }
                    return classes;
                });
            }
        }
        return out;
    }


    static @Nullable JsonNode readCheck(String aText)
    {
        if (aText.isBlank())
        {
            return null;
        }
        try
        {
            JsonNode node = AuthoredRuleAssembler.readYamlTree(aText);
            return node != null && node.isObject() ? node : null;
        }
        catch (JsonProcessingException | RuntimeException e)
        {
            return null;
        }
    }

    // ---- search -----------------------------------------------------------------------------


    /**
     * Free text (ANDed words, quoted phrases — {@link RuleSearch#parse}) and the filters, all
     * ANDed; hits in id order.
     */
    public RuleSearch.Result search(Access.View aView, String aQuery, Filter aFilter, int aLimit)
        throws IOException
    {
        Index index = of(aView);
        List<RuleSearch.Term> terms = RuleSearch.parse(aQuery);
        int limit = Math.max(1, Math.min(aLimit, RuleSearch.MAX_LIMIT));
        List<RuleSearch.Hit> hits = new ArrayList<>();
        int total = 0;
        if (terms.isEmpty() && aFilter.isEmpty())
        {
            return new RuleSearch.Result(aQuery, hits, 0, false);
        }
        for (RuleSummary r : index.rules().values())
        {
            if (!matchesFilter(index, r, aFilter))
            {
                continue;
            }
            if (!terms.isEmpty())
            {
                String blob = (r.id() + "\n" + r.checkText() + "\n" + r.docsText())
                        .toLowerCase(Locale.ROOT);
                if (!RuleSearch.matches(blob, terms))
                {
                    continue;
                }
            }
            total++;
            if (hits.size() < limit)
            {
                List<RuleSearch.Snippet> snippets = new ArrayList<>();
                RuleSearch.snippets(snippets, "check", r.checkText(), terms);
                RuleSearch.snippets(snippets, "docs", r.docsText(), terms);
                hits.add(new RuleSearch.Hit(r.id(), r.family(), r.path(), r.docsPath(),
                        r.description(), snippets));
            }
        }
        return new RuleSearch.Result(aQuery, hits, total, total > hits.size());
    }


    static boolean matchesFilter(Index aIndex, RuleSummary aRule, Filter aFilter)
    {
        String variable = aFilter.variable();
        if (!Filter.blank(variable) && variable != null
                && !mentionsVariable(aIndex, aRule, variable.strip().toUpperCase(Locale.ROOT)))
        {
            return false;
        }
        String scope = aFilter.scope();
        if (!Filter.blank(scope) && scope != null)
        {
            String s = scope.strip().toUpperCase(Locale.ROOT);
            // an explicit scope, never ALL: "scoped to AE" is not "may run on AE"
            if (!aRule.domains().contains(s) && !aRule.classes().contains(s)
                    && !aIndex.classAdmits(aRule, s))
            {
                return false;
            }
        }
        String function = aFilter.function();
        if (!Filter.blank(function) && function != null
                && !aRule.functions().contains(function.strip()))
        {
            return false;
        }
        String status = aFilter.status();
        if (!Filter.blank(status) && status != null
                && !aRule.status().equalsIgnoreCase(status.strip()))
        {
            return false;
        }
        String standard = aFilter.standard();
        String version = aFilter.version();
        if (!Filter.blank(standard) || !Filter.blank(version))
        {
            boolean any = false;
            for (StandardVersion sv : aRule.standards())
            {
                boolean nameOk = Filter.blank(standard) || standard == null
                        || sv.name().equalsIgnoreCase(standard.strip());
                boolean versionOk = Filter.blank(version) || version == null
                        || sv.version().equalsIgnoreCase(version.strip());
                if (nameOk && versionOk)
                {
                    any = true;
                    break;
                }
            }
            if (!any)
            {
                return false;
            }
        }
        return !aFilter.noScenarios() || !aRule.hasScenarios();
    }


    /**
     * "Mentions variable" (§5.3): the rule names it, or names its {@code --} form and its scope
     * reaches the variable's domain ({@code AETERM} finds a {@code --TERM} rule scoped to AE or to
     * the Events class). Case-insensitive (names ruling).
     */
    static boolean mentionsVariable(Index aIndex, RuleSummary aRule, String aVariable)
    {
        if (aRule.referenced().contains(aVariable))
        {
            return true;
        }
        if (aVariable.length() > 2 && !aVariable.startsWith("--"))
        {
            String wildcard = "--" + aVariable.substring(2);
            if (aRule.referenced().contains(wildcard)
                    && aIndex.admits(aRule, aVariable.substring(0, 2)))
            {
                return true;
            }
        }
        if (aVariable.startsWith("--"))
        {
            String suffix = aVariable.substring(2);
            for (String ref : aRule.referenced())
            {
                if (ref.length() == suffix.length() + 2 && ref.endsWith(suffix))
                {
                    return true;
                }
            }
        }
        return false;
    }
}
