package net.cumba.corej.editor.git;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * The tree of one commit, read from the object database (§5.8.4): what the anonymous clone serves.
 * Immutable by construction — a later fetch moves the ref, never this snapshot.
 */
public final class SnapshotSource implements CorpusSource
{

    private final Repository repository;

    private final ObjectId commit;

    public SnapshotSource(Repository aRepository, ObjectId aCommit)
    {
        repository = aRepository;
        commit = aCommit;
    }


    /** The commit this snapshot reads. */
    public ObjectId commit()
    {
        return commit;
    }


    private RevTree tree() throws IOException
    {
        try (RevWalk walk = new RevWalk(repository))
        {
            return walk.parseCommit(commit).getTree();
        }
    }


    @Override
    public Optional<byte[]> read(String aPath) throws IOException
    {
        try (TreeWalk tw = TreeWalk.forPath(repository, aPath, tree()))
        {
            if (tw == null || (tw.getFileMode(0) != FileMode.REGULAR_FILE
                    && tw.getFileMode(0) != FileMode.EXECUTABLE_FILE))
            {
                return Optional.empty();
            }
            ObjectLoader loader = repository.open(tw.getObjectId(0));
            return Optional.of(loader.getBytes());
        }
    }


    @Override
    public List<TreeEntry> list(String aDirectory) throws IOException
    {
        List<TreeEntry> entries = new ArrayList<>();
        try (TreeWalk tw = TreeWalk.forPath(repository, aDirectory, tree()))
        {
            if (tw == null || !tw.isSubtree())
            {
                return entries;
            }
            tw.enterSubtree();
            while (tw.next())
            {
                String name = tw.getNameString();
                if (name.startsWith("."))
                {
                    continue;
                }
                FileMode mode = tw.getFileMode(0);
                if (mode == FileMode.TREE)
                {
                    entries.add(new TreeEntry(name, true, 0));
                }
                else if (mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE)
                {
                    entries.add(new TreeEntry(name, false,
                            repository.open(tw.getObjectId(0)).getSize()));
                }
                // symlinks and gitlinks are neither listed nor served
            }
        }
        entries.sort(Comparator.comparing(TreeEntry::name));
        return entries;
    }


    @Override
    public boolean exists(String aPath) throws IOException
    {
        try (TreeWalk tw = TreeWalk.forPath(repository, aPath, tree()))
        {
            return tw != null && (tw.getFileMode(0) == FileMode.REGULAR_FILE
                    || tw.getFileMode(0) == FileMode.EXECUTABLE_FILE);
        }
    }
}
