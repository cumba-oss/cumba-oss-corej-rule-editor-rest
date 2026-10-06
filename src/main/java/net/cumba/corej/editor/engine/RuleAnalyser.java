package net.cumba.corej.editor.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.authoring.AssembledRule;
import net.cumba.corej.authoring.AuthoredRuleAssembler;
import net.cumba.corej.authoring.RuleDiagnostic;
import net.cumba.corej.authoring.RuleInsight;
import net.cumba.corej.authoring.RuleInsight.DerivedFields;
import net.cumba.corej.authoring.RuleInsight.ScopeEntry;
import net.cumba.corej.authoring.RuleInsight.ScopeUniverse;
import net.cumba.corej.core.metadata.store.MetadataStore;
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
 */
@Component
public class RuleAnalyser
{

    static final String SENSITIVITY_UNDERIVABLE = "SENSITIVITY_UNDERIVABLE";

    private final EngineStore store;

    private final PackageStandards standards;

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
        return toAnalysis(assembled, check);
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


    @SuppressWarnings("PMD.CloseResource") // the store is shared, owned and closed by EngineStore
    Analysis toAnalysis(AssembledRule aAssembled, @Nullable JsonNode aCheck)
    {
        List<Analysis.Diagnostic> diagnostics = new ArrayList<>();
        for (RuleDiagnostic d : aAssembled.diagnostics())
        {
            if (aAssembled.parked() && SENSITIVITY_UNDERIVABLE.equals(d.code()))
            {
                continue;
            }
            diagnostics.add(toDiagnostic(d));
        }
        Rule rule = aAssembled.rule();
        List<String> products = aCheck == null ? List.of()
                : RuleInsight.productKeysOf(AuthoredRuleAssembler.standardsOf(aCheck));
        Analysis.Derived derived = null;
        Analysis.Scope scope = Analysis.Scope.empty(products);
        List<Analysis.VariableRef> refs = new ArrayList<>();
        if (rule != null)
        {
            derived = toDerived(RuleInsight.derived(rule), aAssembled.canonicalNode());
            for (RuleInsight.VariableRef r : RuleInsight.referencedVariables(rule))
            {
                refs.add(new Analysis.VariableRef(r.name(), r.where(), r.quoted()));
            }
            MetadataStore open = store.store().orElse(null);
            if (open != null && !products.isEmpty())
            {
                ScopeUniverse universe = RuleInsight.scopeUniverse(rule, open, products);
                scope = toScope(open, universe, products);
            }
        }
        return new Analysis(aAssembled.coreId(), rule != null, aAssembled.parked(), diagnostics,
                derived, scope, refs, bindingsOf(aCheck), store.available());
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
