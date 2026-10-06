package net.cumba.corej.editor.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.cumba.corej.authoring.AuthoredRuleAssembler;
import net.cumba.corej.authoring.AuthoredRuleAssembler.StandardVersion;
import net.cumba.corej.authoring.PackageStandardsMap;
import net.cumba.corej.core.model.StandardRef;
import net.cumba.corej.editor.git.CorpusSource;
import org.springframework.stereotype.Component;

/**
 * The package standards a rule is assembled with: the first {@code (Name, Version)} of its
 * {@code Standards} names the package the corpus generator would put it in first, and
 * {@link PackageStandardsMap#forPackage} is that package's declaration
 * ({@code rules-src/package-standards.json}, read from the branch the view serves).
 *
 * <p>
 * ⭐ One implementation: the map is the engine's own reader, which takes a directory, so the
 * branch's file is materialised once per distinct content under a server-owned temporary directory
 * (a small cache keyed by the text — a branch switch or an edit of the file is a new key, a reread
 * of the same bytes is a hit). Never re-implemented here.
 * </p>
 */
@Component
public final class PackageStandards implements AutoCloseable
{

    private static final int MAX_CACHED = 8;

    private final Map<String, PackageStandardsMap> cache = new LruCache<>(MAX_CACHED);

    private final Path scratch;

    public PackageStandards() throws IOException
    {
        scratch = Files.createTempDirectory("rule-editor-standards-");
    }


    /** The map of the branch the source serves (empty when the branch has no file). */
    public synchronized PackageStandardsMap of(CorpusSource aSource) throws IOException
    {
        String text = CorpusText.readOrEmpty(aSource, CorpusText.PACKAGE_STANDARDS);
        if (text.isBlank())
        {
            return PackageStandardsMap.empty();
        }
        PackageStandardsMap cached = cache.get(text);
        if (cached != null)
        {
            return cached;
        }
        Path dir = Files.createTempDirectory(scratch, "s");
        Path file = dir.resolve(PackageStandardsMap.FILE);
        Path fileDir = dir.resolve(PackageStandardsMap.FILE.getParent());
        Files.createDirectories(fileDir);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        PackageStandardsMap map;
        try
        {
            map = PackageStandardsMap.load(dir);
        }
        finally
        {
            Files.deleteIfExists(file);
            Files.deleteIfExists(fileDir);
            Files.deleteIfExists(dir);
        }
        cache.put(text, map);
        return map;
    }


    /**
     * The declared standards of the package an authored check is assembled in: its first
     * {@code Standards} entry's package; empty when the check declares none or the map has no entry
     * for it (then the rule is assembled as a package declaring nothing — the editor still loads
     * and runs it).
     */
    public List<StandardRef> forCheck(CorpusSource aSource, JsonNode aCheck) throws IOException
    {
        List<StandardVersion> versions = AuthoredRuleAssembler.standardsOf(aCheck);
        if (versions.isEmpty())
        {
            return List.of();
        }
        StandardVersion first = versions.get(0);
        return of(aSource).forPackage(first.name(), first.version());
    }


    @Override
    public synchronized void close() throws IOException
    {
        cache.clear();
        Files.deleteIfExists(scratch);
    }
}
