package net.cumba.corej.editor.testing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.cumba.corej.editor.git.CorpusSource;

/** A {@link CorpusSource} over an in-memory map of path → text, for the engine unit tests. */
public final class MapSource implements CorpusSource
{

    private final Map<String, byte[]> files = new LinkedHashMap<>();

    public MapSource(Map<String, String> aFiles)
    {
        aFiles.forEach((p, t) -> files.put(p, t.getBytes(StandardCharsets.UTF_8)));
    }


    public MapSource put(String aPath, String aText)
    {
        files.put(aPath, aText.getBytes(StandardCharsets.UTF_8));
        return this;
    }


    public MapSource remove(String aPath)
    {
        files.remove(aPath);
        return this;
    }


    @Override
    public Optional<byte[]> read(String aPath)
    {
        return Optional.ofNullable(files.get(aPath));
    }


    @Override
    public List<TreeEntry> list(String aDirectory)
    {
        String prefix = aDirectory.endsWith("/") ? aDirectory : aDirectory + "/";
        Set<String> seen = new LinkedHashSet<>();
        List<TreeEntry> out = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : files.entrySet())
        {
            if (!e.getKey().startsWith(prefix))
            {
                continue;
            }
            String rest = e.getKey().substring(prefix.length());
            int slash = rest.indexOf('/');
            String name = slash < 0 ? rest : rest.substring(0, slash);
            if (seen.add(name))
            {
                out.add(new TreeEntry(name, slash >= 0, slash >= 0 ? 0 : e.getValue().length));
            }
        }
        out.sort(Comparator.comparing(TreeEntry::name));
        return out;
    }


    @Override
    public boolean exists(String aPath)
    {
        return files.containsKey(aPath);
    }
}
