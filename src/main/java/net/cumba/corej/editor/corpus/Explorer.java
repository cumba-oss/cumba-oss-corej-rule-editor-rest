package net.cumba.corej.editor.corpus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.editor.git.ChangedFile;
import net.cumba.corej.editor.git.CorpusSource;
import org.jspecify.annotations.Nullable;

/**
 * The Explorer's server side (§5.3): families → rules from {@code rules-src/checks/<FAMILY>/}, each
 * with its docs sidecar (when present) and a marker derived from {@code git status} — a rule is NEW
 * when its check file is untracked or added, MODIFIED when the check or the docs file changed. A
 * snapshot (anonymous mode) passes no changes and every rule is CLEAN.
 */
public final class Explorer
{

    /** The marker on a rule in the tree. */
    public enum Marker
    {
        CLEAN, MODIFIED, NEW
    }


    /** One rule row. */
    public record Rule(String id, String path, @Nullable String docsPath, Marker marker)
    {
    }


    /** One family node. */
    public record Family(String name, List<Rule> rules)
    {

        public Family
        {
            rules = List.copyOf(rules);
        }
    }

    private Explorer()
    {
    }


    /**
     * Builds the tree.
     *
     * @param aChanges
     *            path → change kind from the workspace status; empty for a snapshot
     */
    public static List<Family> families(CorpusSource aSource,
            Map<String, ChangedFile.Kind> aChanges)
        throws IOException
    {
        List<Family> families = new ArrayList<>();
        for (CorpusSource.TreeEntry familyDir : aSource.list(CorpusLayout.CHECKS))
        {
            if (!familyDir.directory())
            {
                continue;
            }
            String family = familyDir.name();
            List<Rule> rules = new ArrayList<>();
            for (CorpusSource.TreeEntry file : aSource.list(CorpusLayout.CHECKS + "/" + family))
            {
                if (file.directory() || !CorpusLayout.isCheckFile(file.name()))
                {
                    continue;
                }
                String id = CorpusLayout.ruleId(file.name());
                String path = CorpusLayout.checkPath(family, id);
                String docs = CorpusLayout.docsPath(family, id);
                boolean hasDocs = aSource.exists(docs);
                rules.add(new Rule(id, path, hasDocs ? docs : null,
                        marker(aChanges.get(path), hasDocs ? aChanges.get(docs) : null)));
            }
            families.add(new Family(family, rules));
        }
        return families;
    }


    static Marker marker(ChangedFile.@Nullable Kind aCheck, ChangedFile.@Nullable Kind aDocs)
    {
        if (aCheck == ChangedFile.Kind.UNTRACKED || aCheck == ChangedFile.Kind.ADDED)
        {
            return Marker.NEW;
        }
        if (aCheck != null || aDocs != null)
        {
            return Marker.MODIFIED;
        }
        return Marker.CLEAN;
    }
}
