package net.cumba.corej.editor.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.authoring.FunctionHelp;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.Parameter;
import org.jspecify.annotations.Nullable;

/**
 * The functions of the rule language for completion and tooltips (PLAN-rule-editor §5.4): every
 * {@code FunctionRegistry} descriptor (the signature — the engine's truth) joined with the help
 * text of the branch's {@code rule-guide/reference/functions.md} through the engine's own
 * {@code FunctionHelp} parser (authoring plan P4). A function the guide does not describe is still
 * listed with its signature; a guide entry for a function the engine lacks is dropped.
 */
public final class FunctionCatalogue
{

    /**
     * One function.
     *
     * @param name
     *            the registry name
     * @param kind
     *            {@code VALUE} | {@code BOOLEAN}
     * @param signature
     *            {@code name(p1, p2?, ...rest)} from the registry
     * @param parameters
     *            the registry parameters, described where the guide describes them
     * @param returnType
     *            the guide's return type, or {@code null}
     * @param summary
     *            the guide's one-line summary, or {@code null}
     * @param sections
     *            the guide's further sections (examples, notes), or empty
     */
    public record FunctionInfo(String name, String kind, String signature,
            List<FunctionParameter> parameters, @Nullable String returnType,
            @Nullable String summary, Map<String, String> sections)
    {

        public FunctionInfo
        {
            parameters = List.copyOf(parameters);
            sections = Map.copyOf(sections);
        }
    }


    /** One parameter: the registry's facts plus the guide's description. */
    public record FunctionParameter(String name, String type, boolean required, boolean collector,
            @Nullable String description)
    {
    }

    private FunctionCatalogue()
    {
    }


    /** The catalogue for a guide text (empty text: signatures only). */
    public static List<FunctionInfo> build(String aFunctionsMarkdown)
    {
        FunctionHelp help = aFunctionsMarkdown.isBlank() ? null
                : FunctionHelp.parse(aFunctionsMarkdown);
        List<FunctionInfo> out = new ArrayList<>();
        for (FunctionDescriptor d : FunctionRegistry.all())
        {
            FunctionHelp.Entry entry = help == null ? null : help.entry(d.name());
            List<FunctionParameter> parameters = new ArrayList<>();
            for (Parameter p : d.parameters())
            {
                String description = null;
                if (entry != null)
                {
                    for (FunctionHelp.Parameter hp : entry.parameters())
                    {
                        if (hp.name().equals(p.name()))
                        {
                            description = hp.description();
                        }
                    }
                }
                parameters.add(new FunctionParameter(p.name(), String.valueOf(p.type()),
                        p.required(), p.collector(), description));
            }
            out.add(new FunctionInfo(d.name(), d.kind().name(), signature(d), parameters,
                    entry == null ? null : entry.returnType(),
                    entry == null ? null : entry.summary(),
                    entry == null ? Map.of() : entry.sections()));
        }
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return out;
    }


    static String signature(FunctionDescriptor aDescriptor)
    {
        StringBuilder sb = new StringBuilder(aDescriptor.name()).append('(');
        boolean first = true;
        for (Parameter p : aDescriptor.parameters())
        {
            if (!first)
            {
                sb.append(", ");
            }
            first = false;
            if (p.collector())
            {
                sb.append("...");
            }
            sb.append(p.name());
            if (!p.required())
            {
                sb.append('?');
            }
        }
        return sb.append(')').toString();
    }
}
