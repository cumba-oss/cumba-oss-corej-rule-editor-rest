package net.cumba.corej.editor.git;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A base source with some files replaced, added or deleted: the working tree's uncommitted corpus
 * files, read under the workspace lock, laid over the immutable HEAD snapshot (REST review #5). The
 * result reads like the working tree did at that moment and stays valid after the lock is released,
 * so a long read — the corpus index build — no longer holds the lock, and a save during it cannot
 * tear it.
 *
 * <p>
 * A listing adds the overlay's new files and the directories that lead to them and drops its
 * deleted files; a directory whose every file was deleted is still listed (as the working tree
 * keeps an emptied directory too). Names starting with {@code .} are never listed, as in the two
 * base sources.
 * </p>
 */
public final class OverlaySource implements CorpusSource
{

    private final CorpusSource base;

    private final Map<String, Optional<byte[]>> overlay;

    /**
     * @param aBase
     *            the source underneath (must itself stay valid: a snapshot)
     * @param aOverlay
     *            repository path → content, empty for a deleted file; the byte arrays are taken
     *            over, never handed out
     */
    public OverlaySource(CorpusSource aBase, Map<String, Optional<byte[]>> aOverlay)
    {
        base = aBase;
        overlay = new TreeMap<>(aOverlay);
    }


    @Override
    public Optional<byte[]> read(String aPath) throws IOException
    {
        Optional<byte[]> over = overlay.get(aPath);
        if (over != null)
        {
            return over.map(byte[]::clone);
        }
        return base.read(aPath);
    }


    @Override
    public List<TreeEntry> list(String aDirectory) throws IOException
    {
        String prefix = aDirectory.isEmpty() || aDirectory.endsWith("/") ? aDirectory
                : aDirectory + "/";
        Map<String, TreeEntry> entries = new TreeMap<>();
        for (TreeEntry e : base.list(aDirectory))
        {
            entries.put(e.name(), e);
        }
        for (Map.Entry<String, Optional<byte[]>> o : overlay.entrySet())
        {
            if (!o.getKey().startsWith(prefix))
            {
                continue;
            }
            String rest = o.getKey().substring(prefix.length());
            int slash = rest.indexOf('/');
            String name = slash < 0 ? rest : rest.substring(0, slash);
            if (name.isEmpty() || name.startsWith("."))
            {
                continue;
            }
            Optional<byte[]> content = o.getValue();
            if (slash >= 0)
            {
                if (content.isPresent())
                {
                    entries.putIfAbsent(name, new TreeEntry(name, true, 0));
                }
            }
            else if (content.isPresent())
            {
                entries.put(name, new TreeEntry(name, false, content.get().length));
            }
            else
            {
                TreeEntry gone = entries.get(name);
                if (gone != null && !gone.directory())
                {
                    entries.remove(name);
                }
            }
        }
        List<TreeEntry> out = new ArrayList<>(entries.values());
        out.sort(Comparator.comparing(TreeEntry::name));
        return out;
    }


    @Override
    public boolean exists(String aPath) throws IOException
    {
        Optional<byte[]> over = overlay.get(aPath);
        if (over != null)
        {
            return over.isPresent();
        }
        return base.exists(aPath);
    }
}
