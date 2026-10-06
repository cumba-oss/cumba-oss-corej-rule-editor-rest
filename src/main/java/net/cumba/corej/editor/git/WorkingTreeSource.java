package net.cumba.corej.editor.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** A clone's working tree, read through {@link PathPolicy#resolve} on every access. */
public final class WorkingTreeSource implements CorpusSource
{

    private final Path root;

    public WorkingTreeSource(Path aRoot)
    {
        root = aRoot;
    }


    @Override
    public Optional<byte[]> read(String aPath) throws IOException
    {
        Path p = PathPolicy.resolve(root, aPath);
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
        {
            return Optional.empty();
        }
        return Optional.of(Files.readAllBytes(p));
    }


    @Override
    public List<TreeEntry> list(String aDirectory) throws IOException
    {
        Path dir = PathPolicy.resolve(root, aDirectory);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
        {
            return List.of();
        }
        List<TreeEntry> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir))
        {
            for (Path child : children.toList())
            {
                if (Files.isSymbolicLink(child))
                {
                    continue; // never listed, never served (§5.8.2)
                }
                boolean directory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                String name = child.getFileName().toString();
                if (name.startsWith("."))
                {
                    continue;
                }
                entries.add(new TreeEntry(name, directory, directory ? 0 : Files.size(child)));
            }
        }
        entries.sort(Comparator.comparing(TreeEntry::name));
        return entries;
    }


    @Override
    public boolean exists(String aPath) throws IOException
    {
        return Files.isRegularFile(PathPolicy.resolve(root, aPath), LinkOption.NOFOLLOW_LINKS);
    }
}
