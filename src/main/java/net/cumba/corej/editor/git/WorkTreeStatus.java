package net.cumba.corej.editor.git;

import java.util.List;
import java.util.stream.Stream;

/**
 * The uncommitted state of a workspace: the changes under the editable trees (what a commit stages)
 * and any change outside them (never produced through the API; reported, never staged).
 *
 * @param allowed
 *            changes under {@link PathPolicy#ROOTS}
 * @param ignored
 *            changes elsewhere in the clone
 */
public record WorkTreeStatus(List<ChangedFile> allowed, List<ChangedFile> ignored)
{

    public WorkTreeStatus
    {
        allowed = List.copyOf(allowed);
        ignored = List.copyOf(ignored);
    }


    public boolean isClean()
    {
        return allowed.isEmpty() && ignored.isEmpty();
    }


    /** Every changed path, allowed first. */
    public List<String> paths()
    {
        return Stream.concat(allowed.stream(), ignored.stream()).map(ChangedFile::path).toList();
    }
}
