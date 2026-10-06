package net.cumba.corej.editor.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import net.cumba.corej.authoring.RuleInsight.ScopeEntry;
import net.cumba.corej.authoring.RuleInsight.ScopeUniverse;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import org.jspecify.annotations.Nullable;

/**
 * The variable catalogue of a scope universe: for every in-scope dataset of every product the
 * variables the store declares — the dataset's own, its class's and the model class's ({@code --}
 * forms), an ADaM structure's variable sets — with label, type, core, role and codelists for the
 * tooltips (PLAN-rule-editor §5.4, O6). Which datasets are in scope is the engine's judgement
 * ({@code RuleInsight.scopeUniverse}); this class only reads the details of what it admitted, so
 * the catalogue can never admit a variable the engine's universe does not name.
 */
final class VariableCatalogue
{

    private VariableCatalogue()
    {
    }


    static List<Analysis.Variable> build(MetadataStore aStore, ScopeUniverse aUniverse)
    {
        Map<String, Entry> entries = new LinkedHashMap<>();
        Map<String, List<ScopeEntry>> byProduct = new LinkedHashMap<>();
        for (ScopeEntry e : aUniverse.entries())
        {
            if (e.inScope())
            {
                byProduct.computeIfAbsent(e.productKey(), k -> new ArrayList<>()).add(e);
            }
        }
        for (Map.Entry<String, List<ScopeEntry>> p : byProduct.entrySet())
        {
            Optional<StoredProduct> product = aStore.product(p.getKey());
            if (product.isEmpty())
            {
                continue;
            }
            Set<String> admitted = new LinkedHashSet<>();
            p.getValue().forEach(e -> admitted.add(e.dataset()));
            collect(aStore, product.get(), admitted, entries);
        }
        // every name the engine's universe lists is in the catalogue, detail or not
        for (String name : aUniverse.variableNames())
        {
            entries.computeIfAbsent(name, n -> new Entry(n, n.startsWith("--")));
        }
        Set<String> datasetNames = new LinkedHashSet<>();
        for (Entry e : entries.values())
        {
            if (!e.classLevel)
            {
                datasetNames.add(e.name);
            }
        }
        List<Analysis.Variable> out = new ArrayList<>(entries.size());
        for (Entry e : entries.values())
        {
            List<String> concrete = e.classLevel ? concreteOf(e.name, datasetNames) : List.of();
            out.add(new Analysis.Variable(e.name, e.label, e.type, e.core, e.role,
                    new ArrayList<>(e.codelists), new ArrayList<>(e.datasets),
                    new ArrayList<>(e.products), e.classLevel, concrete));
        }
        return out;
    }


    /** {@code --TERM} stands for every in-scope dataset variable {@code XXTERM}. */
    static List<String> concreteOf(String aClassName, Collection<String> aDatasetNames)
    {
        String suffix = aClassName.substring(2);
        List<String> out = new ArrayList<>();
        for (String name : aDatasetNames)
        {
            if (name.length() == suffix.length() + 2 && name.endsWith(suffix)
                    && !name.startsWith("--"))
            {
                out.add(name);
            }
        }
        return out;
    }


    private static void collect(MetadataStore aStore, StoredProduct aProduct, Set<String> aAdmitted,
            Map<String, Entry> aEntries)
    {
        StoredProduct model = modelOf(aStore, aProduct);
        Map<String, StoredDataset> datasets = new LinkedHashMap<>();
        Map<String, StoredClass> owners = new LinkedHashMap<>();
        for (StoredDataset ds : aProduct.datasets())
        {
            if (ds.name() != null && !ds.name().isBlank())
            {
                datasets.putIfAbsent(ds.name(), ds);
            }
        }
        for (StoredClass c : aProduct.classes())
        {
            for (StoredDataset ds : c.datasets())
            {
                if (ds.name() != null && !ds.name().isBlank())
                {
                    datasets.putIfAbsent(ds.name(), ds);
                    owners.putIfAbsent(ds.name(), c);
                }
            }
        }
        for (Map.Entry<String, StoredDataset> d : datasets.entrySet())
        {
            if (!aAdmitted.contains(d.getKey()))
            {
                continue;
            }
            StoredDataset ds = d.getValue();
            for (StoredVariable v : ds.variables())
            {
                add(aEntries, v, false, d.getKey(), aProduct.key());
            }
            StoredClass owner = owners.get(d.getKey());
            if (owner != null)
            {
                String className = owner.name() == null ? d.getKey() : owner.name();
                for (StoredVariable v : owner.classVariables())
                {
                    add(aEntries, v, true, className, aProduct.key());
                }
                if (model != null && owner.name() != null)
                {
                    for (StoredClass mc : model.classes())
                    {
                        if (owner.name().equalsIgnoreCase(mc.name()))
                        {
                            for (StoredVariable v : mc.classVariables())
                            {
                                add(aEntries, v, true, className, model.key());
                            }
                        }
                    }
                }
            }
            StoredDataStructure structure = structureOf(aProduct, ds.datasetStructure());
            if (structure != null)
            {
                addStructure(aEntries, structure, d.getKey(), aProduct.key());
            }
        }
        if (datasets.isEmpty())
        {
            for (StoredDataStructure s : aProduct.dataStructures())
            {
                if (s.name() != null && aAdmitted.contains(s.name()))
                {
                    addStructure(aEntries, s, s.name(), aProduct.key());
                }
            }
        }
    }


    private static void addStructure(Map<String, Entry> aEntries, StoredDataStructure aStructure,
            String aDataset, String aProduct)
    {
        for (StoredVariableSet set : aStructure.variableSets())
        {
            for (StoredVariable v : set.variables())
            {
                add(aEntries, v, false, aDataset, aProduct);
            }
        }
    }


    private static @Nullable StoredDataStructure structureOf(StoredProduct aProduct,
            @Nullable String aName)
    {
        if (aName == null)
        {
            return null;
        }
        for (StoredDataStructure s : aProduct.dataStructures())
        {
            if (aName.equalsIgnoreCase(s.name()))
            {
                return s;
            }
        }
        return null;
    }


    /** {@code modelHref} {@code /mdr/sdtm/2-0} is the store key {@code models/sdtm/2-0}. */
    private static @Nullable StoredProduct modelOf(MetadataStore aStore, StoredProduct aProduct)
    {
        String href = aProduct.modelHref();
        if (href == null || href.isBlank())
        {
            return null;
        }
        String key = href.startsWith("/mdr/") ? "models/" + href.substring("/mdr/".length()) : href;
        return aStore.product(key).orElse(null);
    }


    private static void add(Map<String, Entry> aEntries, StoredVariable aVariable,
            boolean aClassLevel, String aDataset, String aProduct)
    {
        String name = aVariable.name();
        if (name == null || name.isBlank())
        {
            return;
        }
        Entry e = aEntries.computeIfAbsent(name, n -> new Entry(n, aClassLevel));
        if (e.label == null)
        {
            e.label = aVariable.label();
        }
        if (e.type == null)
        {
            e.type = aVariable.simpleDatatype();
        }
        if (e.core == null)
        {
            e.core = aVariable.core();
        }
        if (e.role == null)
        {
            e.role = aVariable.role();
        }
        if (aVariable.codelistSubmissionValues() != null)
        {
            e.codelists.addAll(aVariable.codelistSubmissionValues());
        }
        if (aVariable.codelistIds() != null)
        {
            e.codelists.addAll(aVariable.codelistIds());
        }
        e.datasets.add(aDataset);
        e.products.add(aProduct);
    }

    private static final class Entry
    {

        final String name;

        final boolean classLevel;

        @Nullable
        String label;

        @Nullable
        String type;

        @Nullable
        String core;

        @Nullable
        String role;

        final SequencedSet<String> codelists = new LinkedHashSet<>();

        final SequencedSet<String> datasets = new LinkedHashSet<>();

        final SequencedSet<String> products = new LinkedHashSet<>();

        Entry(String aName, boolean aClassLevel)
        {
            name = aName;
            classLevel = aClassLevel;
        }
    }
}
