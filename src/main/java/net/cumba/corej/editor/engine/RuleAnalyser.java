package net.cumba.corej.editor.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.cumba.corej.authoring.AssembledRule;
import net.cumba.corej.authoring.AuthoredRuleAssembler;
import net.cumba.corej.authoring.RuleDiagnostic;
import net.cumba.corej.authoring.RuleInsight;
import net.cumba.corej.authoring.RuleInsight.DerivedFields;
import net.cumba.corej.authoring.RuleInsight.ScopeEntry;
import net.cumba.corej.authoring.RuleInsight.ScopeUniverse;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.model.JoinType;
import net.cumba.corej.core.model.OutputVariableListing;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.StandardRef;
import net.cumba.corej.editor.corpus.CorpusLayout;
import net.cumba.corej.editor.git.CorpusSource;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Analysis of one rule buffer (PLAN-rule-editor §5.4, "analysis on change"): the engine's
 * {@code AuthoredRuleAssembler.assemble} over the buffer and the docs sidecar, then
 * {@code RuleInsight} for the derived fields, the referenced variables and the scope universe.
 * In-process — no data, no execution (§5.8.5); the buffer's size is capped by the controller.
 *
 * <p>
 * ⚠ The engine derives {@code Sensitivity} before it knows a rule is parked, while the corpus gate
 * drops parked rules first — so a parked rule can carry a {@code SENSITIVITY_UNDERIVABLE} error the
 * gate never raises (PLAN-rule-editor §12, left for phase 3). Until the one-line engine fix lands,
 * the editor drops that diagnostic on a parked rule here, in one place.
 * </p>
 *
 * <p>
 * A parked rule is never loaded, so the assembler gives it no {@code Rule} — yet its author edits
 * it (REST review #8). The derived fields, the referenced variables and the scope universe then
 * come from the same check assembled without its {@code Executability} (the engine's own reading,
 * never a re-implementation of the scope), the {@code Authorities} from the parked rule's canonical
 * node; its diagnostics stay the parked assembly's.
 * </p>
 *
 * <p>
 * The scope universe (the editor's variable completion) depends on the rule's {@code Scope} and
 * {@code Standards} only, never on the expression (owner, 2026-10-08): while the author types, the
 * buffer is often momentarily unloadable ({@code empty(}). When neither the buffer nor its unparked
 * reading loads, the universe is judged by the {@link #scopeOnly} reading of the check — its Core,
 * Scope and Standards around a trivially valid Check. Only the universe: the diagnostics, derived
 * fields, references and bindings keep describing the real buffer.
 * </p>
 *
 * <p>
 * The {@code joined} datasets (owner, 2026-10-08: {@code DM.RFSTDTC} on an AE rule) are read from
 * the check's {@code Match_Datasets} as authored — so they survive a check that does not load — and
 * each one's variables are the concrete names of the engine's universe of a {@link #joinedRule
 * minimal rule} scoped to exactly that dataset (a supplemental family: {@code SUPPQUAL}), over the
 * products of the rule's {@code Standards}. That rule depends on the name alone, so the variables
 * are cached per (products, store name, store generation).
 * </p>
 */
@Component
public class RuleAnalyser
{

    static final String SENSITIVITY_UNDERIVABLE = "SENSITIVITY_UNDERIVABLE";

    private final EngineStore store;

    private final PackageStandards standards;

    /** The variables of a joined dataset per (products, name, store generation). */
    private final LruCache<JoinedKey, List<Analysis.Variable>> joinedCache = new LruCache<>(
            JOINED_CACHE_ENTRIES);

    static final int JOINED_CACHE_ENTRIES = 128;

    /** The cache key of a joined dataset's variables: everything {@link #joinedRule} reads. */
    record JoinedKey(List<String> products, String name, long generation)
    {
    }

    public RuleAnalyser(EngineStore aStore, PackageStandards aStandards)
    {
        store = aStore;
        standards = aStandards;
    }


    /**
     * Analyse a check buffer.
     *
     * @param aSource
     *            the branch the rule lives on (the docs sidecar and the package standards)
     * @param aCheckPath
     *            the check file's repository path (names the docs sidecar)
     * @param aCheckText
     *            the buffer
     * @param aDocsText
     *            the docs buffer when the client holds one, else {@code null} to read the sidecar
     *            from the branch
     * @return the analysis
     */
    public Analysis analyse(CorpusSource aSource, String aCheckPath, String aCheckText,
            @Nullable String aDocsText)
        throws IOException
    {
        String docs = aDocsText;
        if (docs == null)
        {
            String docsPath = docsPathOf(aCheckPath);
            docs = docsPath == null ? null : CorpusText.read(aSource, docsPath).orElse(null);
        }
        JsonNode check = readCheck(aCheckText);
        List<StandardRef> packageStandards = check == null ? List.of()
                : standards.forCheck(aSource, check);
        AssembledRule assembled = AuthoredRuleAssembler.assemble(aCheckText, docs,
                packageStandards);
        Rule insight = assembled.rule();
        if (insight == null && assembled.parked() && check instanceof ObjectNode object)
        {
            insight = unparked(object, docs, packageStandards);
        }
        Rule scopeRule = insight;
        if (scopeRule == null && check instanceof ObjectNode object)
        {
            // the author is mid-edit (`empty(`): the variables depend on the Scope alone, so they
            // must not vanish with the expression (owner, 2026-10-08)
            scopeRule = scopeOnly(object, packageStandards);
        }
        return toAnalysis(assembled, check, insight, scopeRule);
    }

    /**
     * The fields the scope-only reading keeps from the authored check: the identity ({@code Core}),
     * the {@code Scope} the universe is judged by, and the {@code Standards} the package is
     * declared with. Everything else — the {@code Check}, {@code Bindings}, {@code Expansion},
     * {@code Match_Datasets}, {@code Outcome}, {@code Requirements}, the wildcards,
     * {@code Executability}, the authored {@code Sensitivity} — is dropped or replaced: it is what
     * the author is editing, and none of it is read by the scope universe.
     */
    static final List<String> SCOPE_ONLY_FIELDS = List.of("Core", "Scope", "Standards");

    /**
     * The rule the check's {@code Scope} alone loads as, for the scope universe when the check
     * itself does not load: the {@link #SCOPE_ONLY_FIELDS} with a trivially valid {@code Check}, a
     * placeholder {@code Description} and {@code Outcome} and an explicit {@code Sensitivity} (so
     * no derivation can fail), assembled and loaded through the same {@code AuthoredRuleAssembler}
     * path as the buffer — the engine's reading of the Scope, never a re-implementation.
     * {@code null} when even that does not load (an invalid Scope, Core or Standards): the universe
     * then stays empty.
     */
    static @Nullable Rule scopeOnly(ObjectNode aCheck, List<StandardRef> aPackageStandards)
    {
        ObjectNode minimal = aCheck.objectNode();
        for (String field : SCOPE_ONLY_FIELDS)
        {
            JsonNode value = aCheck.get(field);
            if (value != null)
            {
                minimal.set(field, value.deepCopy());
            }
        }
        minimal.put("Description", "scope only");
        minimal.put("Sensitivity", "Record");
        minimal.putObject("Check").put("expression", "true");
        minimal.putObject("Outcome").put("Message", "scope only");
        // JSON is YAML: the assembler reads the node's text as it reads the buffer; no docs —
        // the citations are not read by the scope
        return AuthoredRuleAssembler.assemble(minimal.toString(), null, aPackageStandards).rule();
    }


    /** The rule a parked check would load as: the check without its {@code Executability}. */
    static @Nullable Rule unparked(ObjectNode aCheck, @Nullable String aDocs,
            List<StandardRef> aPackageStandards)
    {
        ObjectNode live = aCheck.deepCopy();
        live.remove("Executability");
        // JSON is YAML: the assembler reads the node's text as it reads the buffer
        return AuthoredRuleAssembler.assemble(live.toString(), aDocs, aPackageStandards).rule();
    }


    /** The engine's reading of the buffer, or {@code null} when it is not a YAML mapping. */
    public static @Nullable JsonNode readCheck(String aCheckText)
    {
        try
        {
            JsonNode node = AuthoredRuleAssembler.readYamlTree(aCheckText);
            return node != null && node.isObject() ? node : null;
        }
        catch (JsonProcessingException | RuntimeException e)
        {
            return null;
        }
    }


    /** {@code rules-src/checks/<FAMILY>/<ID>.yaml} → its docs sidecar, else {@code null}. */
    public static @Nullable String docsPathOf(String aCheckPath)
    {
        String prefix = CorpusLayout.CHECKS + "/";
        if (!aCheckPath.startsWith(prefix) || !aCheckPath.endsWith(CorpusLayout.YAML))
        {
            return null;
        }
        String rest = aCheckPath.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || rest.indexOf('/', slash + 1) >= 0)
        {
            return null;
        }
        String family = rest.substring(0, slash);
        String id = CorpusLayout.ruleId(rest.substring(slash + 1));
        return CorpusLayout.docsPath(family, id);
    }


    Analysis toAnalysis(AssembledRule aAssembled, @Nullable JsonNode aCheck)
    {
        return toAnalysis(aAssembled, aCheck, aAssembled.rule(), aAssembled.rule());
    }


    /**
     * @param aInsight
     *            the rule the derived fields and references are read from: the assembled one, or
     *            for a parked rule its {@link #unparked} reading (null when neither loads)
     * @param aScopeRule
     *            the rule the scope universe is judged by: {@code aInsight}, or when that is null
     *            the {@link #scopeOnly} reading of the check (null when that does not load either)
     */
    Analysis toAnalysis(AssembledRule aAssembled, @Nullable JsonNode aCheck,
            @Nullable Rule aInsight, @Nullable Rule aScopeRule)
    {
        // the generation BEFORE the store: a reopen in between files the new store's variables
        // under the old generation, which no later reader asks for — the reverse order could
        // file the old store's under the new one
        long generation = store.generation();
        // ONE read: the store may open mid-run (StoreSeeding), and the scope and storeAvailable
        // must describe the same store
        MetadataStore open = store.store().orElse(null);
        List<Analysis.Diagnostic> diagnostics = new ArrayList<>();
        for (RuleDiagnostic d : aAssembled.diagnostics())
        {
            if (aAssembled.parked() && SENSITIVITY_UNDERIVABLE.equals(d.code()))
            {
                continue;
            }
            diagnostics.add(toDiagnostic(d));
        }
        Rule rule = aInsight;
        List<String> products = aCheck == null ? List.of()
                : RuleInsight.productKeysOf(AuthoredRuleAssembler.standardsOf(aCheck));
        JsonNode canonical = aAssembled.canonicalNode();
        Analysis.Derived derived = rule == null && aAssembled.parked() && canonical != null
                ? parkedDerived(canonical)
                : null;
        Analysis.Scope scope = Analysis.Scope.empty(products);
        List<Analysis.VariableRef> refs = new ArrayList<>();
        if (rule != null)
        {
            derived = toDerived(RuleInsight.derived(rule), aAssembled.canonicalNode());
            for (RuleInsight.VariableRef r : RuleInsight.referencedVariables(rule))
            {
                refs.add(new Analysis.VariableRef(r.name(), r.where(), r.quoted()));
            }
        }
        if (aScopeRule != null && open != null && !products.isEmpty())
        {
            ScopeUniverse universe = RuleInsight.scopeUniverse(aScopeRule, open, products);
            scope = toScope(open, universe, products);
        }
        List<Analysis.Joined> joined = new ArrayList<>();
        for (JoinedEntry entry : joinedEntriesOf(aCheck))
        {
            joined.add(new Analysis.Joined(entry.name(), entry.joinType(),
                    joinedVariables(storeNameOf(entry.name()), open, generation, products)));
        }
        return new Analysis(aAssembled.coreId(), aAssembled.rule() != null, aAssembled.parked(),
                diagnostics, derived, scope, joined, refs, bindingsOf(aCheck),
                requiredVariablesOf(aCheck), open != null);
    }


    /**
     * The variables of one joined dataset: the concrete (non-{@code --}) variables of the engine's
     * universe of {@link #joinedRule} over the rule's products, cached. A class-level {@code --}
     * entry is left out: the engine resolves {@code AE.--TERM} with the PRIMARY's prefix
     * ({@code CheckConditionTransformer.resolveTextWildcard}), so it never names the joined
     * dataset's variable (review H1). Empty without a store, without products, or when the minimal
     * rule does not load (it always should: only the name varies, and it is a plain identifier).
     */
    List<Analysis.Variable> joinedVariables(String aName, @Nullable MetadataStore aStore,
            long aGeneration, List<String> aProducts)
    {
        if (aStore == null || aProducts.isEmpty())
        {
            return List.of();
        }
        JoinedKey key = new JoinedKey(List.copyOf(aProducts), aName.toUpperCase(Locale.ROOT),
                aGeneration);
        synchronized (joinedCache)
        {
            List<Analysis.Variable> cached = joinedCache.get(key);
            if (cached != null)
            {
                return cached;
            }
        }
        // computed outside the lock: two racing requests compute the same immutable answer
        Rule rule = joinedRule(key.name());
        List<Analysis.Variable> variables = rule == null ? List.of()
                : VariableCatalogue
                        .build(aStore, RuleInsight.scopeUniverse(rule, aStore, aProducts)).stream()
                        .filter(v -> !v.classLevel()).toList();
        synchronized (joinedCache)
        {
            joinedCache.put(key, variables);
        }
        return variables;
    }

    /** A plain dataset name: no {@code --}, {@code &TOKEN&}, glob or regex — an identifier. */
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    /** One qualifying {@code Match_Datasets} entry: its name as authored and its join type. */
    record JoinedEntry(String name, String joinType)
    {
    }

    /**
     * The datasets the check's keyed {@code Match_Datasets} entries join, as authored (first
     * qualifying spelling of a name wins with its {@code Join_Type}, names compared
     * case-insensitively, authored order), read from the check's tree so a check that does not load
     * still has them. An entry qualifies when it has a plain {@link #PLAIN_NAME} and a non-empty
     * {@code Keys}, and is none of the entries that build no keyed partner of that name: a
     * {@code Child: true} parent (its columns are the parent's, whatever the name), a
     * {@code RELREC} relationship. A template name ({@code SUPP--}, {@code &DOM&}) is not plain.
     */
    static List<JoinedEntry> joinedEntriesOf(@Nullable JsonNode aCheck)
    {
        List<JoinedEntry> out = new ArrayList<>();
        if (aCheck == null)
        {
            return out;
        }
        JsonNode entries = aCheck.get("Match_Datasets");
        if (entries == null || !entries.isArray())
        {
            return out;
        }
        List<String> seen = new ArrayList<>();
        for (JsonNode entry : entries)
        {
            JoinedEntry joined = joinedEntryOf(entry);
            if (joined != null && !seen.contains(joined.name().toUpperCase(Locale.ROOT)))
            {
                seen.add(joined.name().toUpperCase(Locale.ROOT));
                out.add(joined);
            }
        }
        return out;
    }


    /** The entry when it is an ordinary keyed join, else {@code null}. */
    private static @Nullable JoinedEntry joinedEntryOf(JsonNode aEntry)
    {
        if (!aEntry.isObject())
        {
            return null;
        }
        JsonNode nameNode = aEntry.get("Name");
        JsonNode keys = aEntry.get("Keys");
        JsonNode child = aEntry.get("Child");
        if (nameNode == null || !nameNode.isTextual() || keys == null || !keys.isArray()
                || keys.isEmpty() || (child != null && child.asBoolean(false)))
        {
            return null;
        }
        String name = nameNode.asText().strip();
        if (!PLAIN_NAME.matcher(name).matches() || "RELREC".equals(name.toUpperCase(Locale.ROOT)))
        {
            return null;
        }
        return new JoinedEntry(name, joinTypeOf(aEntry));
    }


    /**
     * The entry's {@code Join_Type} as the engine reads it: absent or blank is {@code inner} (the
     * default {@code RulePackageLoader.normalizeJoinTypes} stamps), a value {@link JoinType} knows
     * (ignoring case, never trimmed) is its lower-case spelling, and anything else — a load error
     * the diagnostics name — is passed through lower-cased, so it never reads as {@code left}.
     */
    static String joinTypeOf(JsonNode aEntry)
    {
        JsonNode node = aEntry.get("Join_Type");
        String raw = node == null || node.isNull() ? null : node.asText();
        if (raw == null || JoinType.isAbsent(raw))
        {
            return JoinType.INNER.getJsonValue();
        }
        JoinType known = JoinType.fromJson(raw);
        return known == null ? raw.toLowerCase(Locale.ROOT) : known.getJsonValue();
    }


    /**
     * The name the store declares a joined dataset's variables under: {@code SUPPQUAL} for a
     * supplemental family ({@code SUPP…} / {@code SQ…}, the engine's own prefix test —
     * {@code JoinKeyTypes}, and {@code MetadataLibraryProvider}'s {@code SUPP--} / {@code SQ--} →
     * {@code SUPPQUAL}), the name itself otherwise.
     */
    static String storeNameOf(String aName)
    {
        String upper = aName.toUpperCase(Locale.ROOT);
        return upper.startsWith("SUPP") || upper.startsWith("SQ") ? SUPPQUAL : aName;
    }

    /** The IG's one supplemental-qualifier dataset, whose columns every keyed SUPP family has. */
    static final String SUPPQUAL = "SUPPQUAL";

    /**
     * The rule whose scope universe is exactly the joined dataset: {@code Scope.Datasets.Include}
     * of the one name, which the engine matches as a literal (case-insensitive, no prefix) against
     * a standard's dataset name — and, for an ADaM IG, which names no datasets, against the data
     * structure's name ({@code ADSL}), the unit {@code RuleInsight.scopeUniverse} judges there. A
     * placeholder Core, Description, Outcome, a trivially valid Check and an explicit Sensitivity,
     * so the load depends on the name alone (the cache key); no Standards — the products are given
     * to {@code scopeUniverse} directly. Loaded through the same {@code AuthoredRuleAssembler} path
     * as the buffer: the engine's reading of the Scope, never a re-implementation.
     */
    static @Nullable Rule joinedRule(String aName)
    {
        ObjectNode minimal = JsonNodeFactory.instance.objectNode();
        minimal.putObject("Core").put("Id", "EDITOR-JOINED").put("Status", "Draft");
        minimal.put("Description", "joined dataset " + aName);
        minimal.put("Sensitivity", "Record");
        minimal.putObject("Scope").putObject("Datasets").putArray("Include").add(aName);
        minimal.putObject("Check").put("expression", "true");
        minimal.putObject("Outcome").put("Message", "joined dataset");
        return AuthoredRuleAssembler.assemble(minimal.toString(), null, List.of()).rule();
    }


    /** A parked rule whose check does not load even unparked: the authorities, nothing derived. */
    static Analysis.Derived parkedDerived(JsonNode aCanonical)
    {
        JsonNode authorities = aCanonical.get("Authorities");
        return new Analysis.Derived(null, "NONE",
                "the rule is parked and its check does not load: nothing is derived",
                new Analysis.OutputVariables(List.of(), List.of(), List.of(), false),
                authorities == null || authorities.isNull() ? null : Json.toJackson3(authorities),
                null, List.of());
    }


    static Analysis.Diagnostic toDiagnostic(RuleDiagnostic aDiagnostic)
    {
        RuleDiagnostic.TextRange r = aDiagnostic.range();
        return new Analysis.Diagnostic(aDiagnostic.code(), aDiagnostic.severity().name(),
                aDiagnostic.message(),
                aDiagnostic.path() == null ? null : aDiagnostic.path().toString(),
                r == null ? null
                        : new Analysis.Range(r.startLine(), r.startColumn(), r.endLine(),
                                r.endColumn()),
                aDiagnostic.document().name());
    }


    static Analysis.Derived toDerived(DerivedFields aFields, @Nullable JsonNode aCanonical)
    {
        OutputVariableListing ov = aFields.outputVariables();
        JsonNode authorities = aCanonical == null ? null : aCanonical.get("Authorities");
        return new Analysis.Derived(
                aFields.sensitivity().value() == null ? null
                        : aFields.sensitivity().value().getJsonValue(),
                aFields.sensitivity().confidence().name(), aFields.sensitivity().rationale(),
                new Analysis.OutputVariables(ov.effective(), ov.authored(), ov.excluded(),
                        ov.derived()),
                authorities == null || authorities.isNull() ? null : Json.toJackson3(authorities),
                aFields.evaluationDomain(), new ArrayList<>(aFields.wildcardNames()));
    }


    static Analysis.Scope toScope(MetadataStore aStore, ScopeUniverse aUniverse,
            List<String> aProducts)
    {
        List<Analysis.ScopeDataset> datasets = new ArrayList<>();
        for (ScopeEntry e : aUniverse.entries())
        {
            datasets.add(new Analysis.ScopeDataset(e.productKey(), e.dataset(), e.datasetClass(),
                    e.dataStructure(), e.inScope(), e.mismatch()));
        }
        return new Analysis.Scope(aProducts, aUniverse.missingProducts(), datasets,
                VariableCatalogue.build(aStore, aUniverse));
    }


    /** The {@code Requirements.Variables.All} then {@code .Any} entries, distinct, as authored. */
    static List<String> requiredVariablesOf(@Nullable JsonNode aCheck)
    {
        List<String> out = new ArrayList<>();
        if (aCheck == null)
        {
            return out;
        }
        JsonNode variables = aCheck.path("Requirements").path("Variables");
        for (String facet : List.of("All", "Any"))
        {
            JsonNode names = variables.path(facet);
            if (names.isArray())
            {
                for (JsonNode n : names)
                {
                    String name = n.asText("").strip();
                    if (!name.isEmpty() && !out.contains(name))
                    {
                        out.add(name);
                    }
                }
            }
        }
        return out;
    }


    /** The {@code Bindings[].name} entries of the buffer, as authored. */
    static List<String> bindingsOf(@Nullable JsonNode aCheck)
    {
        List<String> out = new ArrayList<>();
        if (aCheck == null)
        {
            return out;
        }
        JsonNode bindings = aCheck.get("Bindings");
        if (bindings != null && bindings.isArray())
        {
            for (JsonNode b : bindings)
            {
                JsonNode name = b.get("name");
                if (name != null && name.isTextual() && !name.asText().isBlank())
                {
                    out.add(name.asText());
                }
            }
        }
        return out;
    }
}
