package net.cumba.corej.editor.engine;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the editor learns about one authored rule buffer (PLAN-rule-editor §5.4): the diagnostics of
 * {@code AuthoredRuleAssembler.assemble}, the derived fields of {@code RuleInsight} (shown greyed,
 * never saved — O5), the scope universe over the metadata store (completion, O6) and the variables
 * the rule names. Every record here is a wire shape; the engine's own types stay inside
 * {@link RuleAnalyser}.
 *
 * @param coreId
 *            the rule's {@code Core.Id}, or {@code <unknown>}
 * @param loaded
 *            whether the engine loaded a rule (false for a parked rule or an erroneous buffer)
 * @param parked
 *            {@code Executability: "Not Executable"}
 * @param diagnostics
 *            every diagnostic, errors first as the engine orders them
 * @param derived
 *            the derived fields, or {@code null} when no rule loaded
 * @param scope
 *            the scope universe, empty entries when no store is open
 * @param referencedVariables
 *            every variable the rule names
 * @param bindings
 *            the {@code $binding} names the buffer declares
 * @param storeAvailable
 *            whether the scope universe was computed against a store
 */
public record Analysis(String coreId, boolean loaded, boolean parked, List<Diagnostic> diagnostics,
        @Nullable Derived derived, Scope scope, List<VariableRef> referencedVariables,
        List<String> bindings, boolean storeAvailable)
{

    public Analysis
    {
        diagnostics = List.copyOf(diagnostics);
        referencedVariables = List.copyOf(referencedVariables);
        bindings = List.copyOf(bindings);
    }

    /**
     * One diagnostic, ranged where the engine could place it.
     *
     * @param code
     *            the engine's code ({@code YAML_SYNTAX}, {@code EXPRESSION_SYNTAX},
     *            {@code LOAD_ERROR}, …)
     * @param severity
     *            {@code ERROR} | {@code WARNING} | {@code INFO}
     * @param message
     *            the text
     * @param path
     *            the YAML path ({@code /Scope/Domains/Include/0}), or {@code null}
     * @param range
     *            the 1-based inclusive range, or {@code null}
     * @param document
     *            {@code CHECK} | {@code DOCS} — which authored file the path and range are in
     */
    public record Diagnostic(String code, String severity, String message, @Nullable String path,
            @Nullable Range range, String document)
    {
    }


    /** A 1-based, inclusive text range. */
    public record Range(int startLine, int startColumn, int endLine, int endColumn)
    {
    }


    /**
     * The generated fields (O5).
     *
     * @param sensitivity
     *            the derived {@code Sensitivity}, or {@code null} when underivable
     * @param sensitivityConfidence
     *            the derivation's confidence
     * @param sensitivityRationale
     *            why
     * @param outputVariables
     *            the effective / authored / excluded listing
     * @param authorities
     *            the full {@code Authorities} tree as the canonical node carries it, or
     *            {@code null}
     * @param evaluationDomain
     *            the domain the rule evaluates on, or {@code null}
     * @param wildcardNames
     *            the {@code --} names the Check expands over
     */
    public record Derived(@Nullable String sensitivity, String sensitivityConfidence,
            String sensitivityRationale, OutputVariables outputVariables,
            @Nullable Object authorities, @Nullable String evaluationDomain,
            List<String> wildcardNames)
    {

        public Derived
        {
            wildcardNames = List.copyOf(wildcardNames);
        }
    }


    /** The effective Output_Variables with the authored subset and the struck exclusions. */
    public record OutputVariables(List<String> effective, List<String> authored,
            List<String> excluded, boolean derived)
    {

        public OutputVariables
        {
            effective = List.copyOf(effective);
            authored = List.copyOf(authored);
            excluded = List.copyOf(excluded);
        }
    }


    /**
     * The scope universe (O6): every dataset of the rule's standards judged against its
     * {@code Scope}, and the variables of the admitted ones.
     *
     * @param products
     *            the store product keys of the rule's {@code Standards}
     * @param missingProducts
     *            the keys the store does not hold
     * @param datasets
     *            every judged dataset
     * @param variables
     *            the variable catalogue of the in-scope datasets
     */
    public record Scope(List<String> products, List<String> missingProducts,
            List<ScopeDataset> datasets, List<Variable> variables)
    {

        public Scope
        {
            products = List.copyOf(products);
            missingProducts = List.copyOf(missingProducts);
            datasets = List.copyOf(datasets);
            variables = List.copyOf(variables);
        }


        public static Scope empty(List<String> aProducts)
        {
            return new Scope(aProducts, List.of(), List.of(), List.of());
        }
    }


    /** One dataset of one product, judged. */
    public record ScopeDataset(String product, String name, @Nullable String datasetClass,
            @Nullable String dataStructure, boolean inScope, @Nullable String mismatch)
    {
    }


    /**
     * One variable the scope reaches, with what the store says about it (tooltips, O6).
     *
     * @param name
     *            the name; a class-level variable keeps its {@code --} form
     * @param label
     *            the label
     * @param type
     *            the simple datatype
     * @param core
     *            the core designation
     * @param role
     *            the role
     * @param codelists
     *            the codelist ids / submission values
     * @param datasets
     *            the in-scope datasets (or classes, for a class-level variable) declaring it
     * @param products
     *            the products declaring it
     * @param classLevel
     *            a class / model variable ({@code --} form) rather than a dataset one
     * @param concrete
     *            for a class-level variable: the dataset variables it stands for in scope
     */
    public record Variable(String name, @Nullable String label, @Nullable String type,
            @Nullable String core, @Nullable String role, List<String> codelists,
            List<String> datasets, List<String> products, boolean classLevel, List<String> concrete)
    {

        public Variable
        {
            codelists = List.copyOf(codelists);
            datasets = List.copyOf(datasets);
            products = List.copyOf(products);
            concrete = List.copyOf(concrete);
        }
    }


    /** A variable the rule names: where, and whether as a quoted string argument. */
    public record VariableRef(String name, String where, boolean quoted)
    {
    }
}
