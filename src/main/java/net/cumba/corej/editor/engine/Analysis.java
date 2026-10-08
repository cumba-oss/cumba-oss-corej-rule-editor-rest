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
 * @param joined
 *            the datasets the rule's keyed {@code Match_Datasets} entries join, in authored order,
 *            one per distinct name, each with the variables its qualified references
 *            ({@code DM.RFSTDTC}) can name — see {@link Joined}
 * @param referencedVariables
 *            every variable the rule names
 * @param bindings
 *            the {@code $binding} names the buffer declares
 * @param requiredVariables
 *            the variables the buffer's {@code Requirements.Variables} names ({@code All} then
 *            {@code Any}, as authored) — a new scenario's columns beside the referenced ones
 * @param storeAvailable
 *            whether the scope universe was computed against a store
 */
public record Analysis(String coreId, boolean loaded, boolean parked, List<Diagnostic> diagnostics,
        @Nullable Derived derived, Scope scope, List<Joined> joined,
        List<VariableRef> referencedVariables, List<String> bindings,
        List<String> requiredVariables, boolean storeAvailable)
{

    public Analysis
    {
        diagnostics = List.copyOf(diagnostics);
        joined = List.copyOf(joined);
        referencedVariables = List.copyOf(referencedVariables);
        bindings = List.copyOf(bindings);
        requiredVariables = List.copyOf(requiredVariables);
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


    /**
     * One dataset a keyed {@code Match_Datasets} entry joins (owner, 2026-10-08: completion of
     * {@code DM.RFSTDTC} on an AE rule). Only an ordinary keyed entry with a plain dataset name
     * qualifies: no {@code Child: true} parent (its columns are the parent domain's, whatever the
     * name), no {@code RELREC}, no {@code --} or {@code &TOKEN&} template name (a {@code SUPP--} /
     * {@code SQAP--} family included: it names the primary's own family, resolved per dataset).
     *
     * <p>
     * <b>What resolves.</b> The variables are looked up by the name as the store knows it: a
     * standard SDTM / SEND dataset name ({@code DM}, {@code AE}) and an ADaM IG's data structure
     * name ({@code ADSL} — in practice the only ADaM name a join uses that the store declares). An
     * ADaM dataset such as {@code ADAE}, a sponsor dataset, or an SDTM split member ({@code LB1})
     * is listed with <b>empty</b> variables: the store declares no dataset of that name, and the
     * engine derives a split's base from the dataset's {@code DOMAIN} data, never from the name
     * ({@code ScopeMatcher}: {@code FAAE} is a split of {@code FA}), so no name heuristic is
     * applied here. A keyed supplemental family ({@code SUPPAE}, {@code SQAPAE}) — a valid partner
     * whose columns are the raw supplemental columns ({@code SUPPAE.QNAM}) — is served the IG's
     * {@code SUPPQUAL} variables, the one supplemental dataset the store declares.
     * </p>
     *
     * @param name
     *            the dataset name as first authored
     * @param joinType
     *            the entry's {@code Join_Type} as the engine reads it, lower case: {@code "inner"}
     *            or {@code "left"} ({@code JoinType}, matched ignoring case, never trimmed), and
     *            {@code "inner"} when absent or blank — the default {@code
     *            RulePackageLoader.normalizeJoinTypes} stamps. An unrecognised value is passed
     *            through lower-cased (the rule then fails to load and the diagnostics name it). ⭐
     *            {@code <Name>._matched_} is accepted <b>only when this is {@code "left"}</b>: for
     *            every other value the engine refuses the flag ({@code
     *            StageAErrorKind.MATCHED_FLAG_INNER_JOIN}, constant-true under an inner join) — for
     *            a supplemental family too, which keeps every primary row whatever its
     *            {@code Join_Type} but whose absent value is still stamped {@code inner}
     * @param variables
     *            the concrete variables the store declares for that dataset in the rule's
     *            standards, judged by the engine exactly as a rule scoped to that one dataset;
     *            class-level {@code --} entries are left out — the engine resolves a joined
     *            {@code AE.--TERM} with the <b>primary's</b> prefix
     *            ({@code CheckConditionTransformer.resolveTextWildcard}), so on a CM rule it would
     *            read {@code AE.CMTERM}, never {@code AE.AETERM}; empty without a store (the entry
     *            is still listed) and for a name the store does not declare (see above)
     */
    public record Joined(String name, String joinType, List<Variable> variables)
    {

        public Joined
        {
            variables = List.copyOf(variables);
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
