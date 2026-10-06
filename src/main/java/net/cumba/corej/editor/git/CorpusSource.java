package net.cumba.corej.editor.git;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Read access to the rule corpus the explorer and the search walk: a user's working tree
 * ({@link WorkingTreeSource}) or a commit snapshot of the anonymous clone ({@link SnapshotSource},
 * §5.8.4: never the anonymous clone's working tree, so a running fetch cannot tear a read). Paths
 * are repository-relative and already {@link PathPolicy#check checked}.
 */
public interface CorpusSource
{

    /** A directory entry. */
    record TreeEntry(String name, boolean directory, long size)
    {
    }

    /** The bytes of a regular file, empty when absent (or a directory / link). */
    Optional<byte[]> read(String aPath) throws IOException;


    /** The entries of a directory, sorted by name; empty when the directory does not exist. */
    List<TreeEntry> list(String aDirectory) throws IOException;


    /** Whether a regular file exists at the path. */
    boolean exists(String aPath) throws IOException;
}
