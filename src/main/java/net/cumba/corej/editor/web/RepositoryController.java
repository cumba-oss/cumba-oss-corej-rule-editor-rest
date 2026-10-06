package net.cumba.corej.editor.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.config.RepositoryCoordinates;
import net.cumba.corej.editor.error.Errors;
import net.cumba.corej.editor.forge.ForgeClient;
import net.cumba.corej.editor.git.ChangedFile;
import net.cumba.corej.editor.git.CommitInfo;
import net.cumba.corej.editor.git.MergeOutcome;
import net.cumba.corej.editor.git.PushOutcome;
import net.cumba.corej.editor.git.WorkTreeStatus;
import net.cumba.corej.editor.session.SessionUser;
import net.cumba.corej.editor.web.dto.Dtos;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The workspace's git surface (§5.2): status, branches, create / switch / delete, commit, discard,
 * push, update from default and from the remote branch. Every mutating endpoint checks the mode
 * (403 {@code READ_ONLY}) and the client's {@code {branch, head}} (409 {@code STALE}) before
 * acting; every branch name is checked as a ref name before JGit sees it (400 {@code BAD_REQUEST},
 * security review L6).
 *
 * <p>
 * ⚠ {@code GET /api/branches?fetch=true} has a side effect, accepted on purpose (security review
 * INFO): it only refreshes the remote-tracking refs with the user's own token — no write, no merge
 * — so a cross-site top-level navigation that triggers it (a {@code SameSite=Lax} cookie is sent on
 * one) changes nothing the user owns.
 * </p>
 */
@RestController
@RequestMapping("/api")
@Tag(name = "repository", description = "Branches, status, commit, push, update")
public class RepositoryController
{

    private final Access access;

    private final ForgeClient forge;

    private final RepositoryCoordinates repository;

    public RepositoryController(Access aAccess, ForgeClient aForge, EditorProperties aProperties)
    {
        access = aAccess;
        forge = aForge;
        repository = aProperties.coordinates();
    }


    @GetMapping("/status")
    @Operation(
            summary = "The dirty states the server tracks: uncommitted files and unpushed commits")
    public Dtos.Status status(@AuthenticationPrincipal SessionUser aUser,
            HttpServletRequest aRequest)
        throws IOException
    {
        return access.workspace(aUser, aRequest, (ws, c) ->
        {
            WorkTreeStatus st = ws.status();
            String branch = ws.currentBranch();
            return new Dtos.Status(branch, ws.head().name(), ws.defaultBranch(), !aUser.canWrite(),
                    st.allowed(), st.ignored(), ws.unpushed(branch));
        });
    }


    @GetMapping("/branches")
    @Operation(
            summary = "Local and remote branches; fetch=true refreshes (and prunes) from the remote first")
    public Dtos.Branches branches(@AuthenticationPrincipal SessionUser aUser,
            @RequestParam(name = "fetch", defaultValue = "false") boolean aFetch,
            HttpServletRequest aRequest)
        throws IOException
    {
        return access.workspace(aUser, aRequest, (ws, c) ->
        {
            if (aFetch)
            {
                ws.fetch(c.git());
            }
            return new Dtos.Branches(ws.currentBranch(), ws.defaultBranch(), ws.head().name(),
                    ws.branches());
        });
    }


    @PostMapping("/branches")
    @Operation(
            summary = "Create a branch from the remote default branch and switch to it (clean tree required)")
    public Dtos.Branches create(@AuthenticationPrincipal SessionUser aUser,
            @RequestBody Dtos.CreateBranch aBody, HttpServletRequest aRequest)
        throws IOException
    {
        aUser.requireEditMode();
        requireName(aBody.name());
        return access.workspace(aUser, aRequest, (ws, c) ->
        {
            ws.requireExpected(aBody.branch(), aBody.head());
            ws.createBranch(aBody.name(), c.git());
            return new Dtos.Branches(ws.currentBranch(), ws.defaultBranch(), ws.head().name(),
                    ws.branches());
        });
    }


    @PostMapping("/branches/switch")
    @Operation(summary = "Check out a local or remote branch (clean tree required)")
    public Dtos.Branches switchTo(@AuthenticationPrincipal SessionUser aUser,
            @RequestBody Dtos.SwitchBranch aBody, HttpServletRequest aRequest)
        throws IOException
    {
        requireName(aBody.name());
        return access.workspace(aUser, aRequest, (ws, c) ->
        {
            ws.requireExpected(aBody.branch(), aBody.head());
            ws.switchBranch(aBody.name());
            return new Dtos.Branches(ws.currentBranch(), ws.defaultBranch(), ws.head().name(),
                    ws.branches());
        });
    }


    @PostMapping("/branches/delete")
    @Operation(
            summary = "Delete a local branch that holds nothing unpushed (a merged, pruned branch)")
    public Dtos.Branches delete(@AuthenticationPrincipal SessionUser aUser,
            @RequestBody Dtos.DeleteBranch aBody, HttpServletRequest aRequest)
        throws IOException
    {
        requireName(aBody.name());
        return access.workspace(aUser, aRequest, (ws, c) ->
        {
            ws.deleteBranch(aBody.name());
            return new Dtos.Branches(ws.currentBranch(), ws.defaultBranch(), ws.head().name(),
                    ws.branches());
        });
    }


    @PostMapping("/commit")
    @Operation(
            summary = "Stage every saved change under the editable trees and commit it (hooks off)")
    public Dtos.Committed commit(@AuthenticationPrincipal SessionUser aUser,
            @RequestBody Dtos.Commit aBody, HttpServletRequest aRequest)
        throws IOException
    {
        aUser.requireEditMode();
        if (aBody.message() == null || aBody.message().isBlank())
        {
            throw Errors.badRequest("a commit message is required");
        }
        return access.workspace(aUser, aRequest, (ws, c) ->
        {
            ws.requireExpected(aBody.branch(), aBody.head());
            CommitInfo commit = ws.commit(aBody.message().strip(), aUser.name(), aUser.email());
            return new Dtos.Committed(commit, ws.currentBranch(), ws.head().name());
        });
    }


    @PostMapping("/discard")
    @Operation(
            summary = "Discard every saved, uncommitted change under the editable trees: restore tracked files from HEAD, delete new ones (the confirmed paths must be the current ones)")
    public Dtos.Discarded discard(@AuthenticationPrincipal SessionUser aUser,
            @RequestBody Dtos.Discard aBody, HttpServletRequest aRequest)
        throws IOException
    {
        aUser.requireEditMode();
        List<String> paths = aBody.paths();
        if (paths == null)
        {
            throw Errors.badRequest("the confirmed paths are required");
        }
        return access.workspace(aUser, aRequest, (ws, c) ->
        {
            ws.requireExpected(aBody.branch(), aBody.head());
            List<ChangedFile> discarded = ws.discard(paths);
            return new Dtos.Discarded(discarded, ws.currentBranch(), ws.head().name());
        });
    }


    @PostMapping("/push")
    @Operation(
            summary = "Push the current branch, never forced; answers the create-pull-request link")
    public Dtos.Pushed push(@AuthenticationPrincipal SessionUser aUser,
            @RequestBody Dtos.Expect aBody, HttpServletRequest aRequest)
        throws IOException
    {
        aUser.requireEditMode();
        return access.workspace(aUser, aRequest, (ws, c) ->
        {
            ws.requireExpected(aBody.branch(), aBody.head());
            PushOutcome out = ws.push(c.git());
            return new Dtos.Pushed(out,
                    forge.pullRequestUrl(repository, ws.defaultBranch(), out.branch()));
        });
    }


    @PostMapping("/branches/update-from-default")
    @Operation(
            summary = "Fetch and merge the remote default branch into the current branch (clean tree; conflicts abort)")
    public Dtos.Updated updateFromDefault(@AuthenticationPrincipal SessionUser aUser,
            @RequestBody Dtos.Expect aBody, HttpServletRequest aRequest)
        throws IOException
    {
        aUser.requireEditMode();
        return access.workspace(aUser, aRequest, (ws, c) ->
        {
            ws.requireExpected(aBody.branch(), aBody.head());
            MergeOutcome out = ws.updateFrom(ws.defaultBranch(), c.git());
            return new Dtos.Updated(out, ws.currentBranch(), ws.head().name());
        });
    }


    @PostMapping("/branches/update-from-remote")
    @Operation(
            summary = "Fetch and merge the remote counterpart of the current branch (after a non-fast-forward push)")
    public Dtos.Updated updateFromRemote(@AuthenticationPrincipal SessionUser aUser,
            @RequestBody Dtos.Expect aBody, HttpServletRequest aRequest)
        throws IOException
    {
        aUser.requireEditMode();
        return access.workspace(aUser, aRequest, (ws, c) ->
        {
            ws.requireExpected(aBody.branch(), aBody.head());
            MergeOutcome out = ws.updateFrom(ws.currentBranch(), c.git());
            return new Dtos.Updated(out, ws.currentBranch(), ws.head().name());
        });
    }


    private static void requireName(String aName)
    {
        if (aName == null || aName.isBlank())
        {
            throw Errors.badRequest("a branch name is required");
        }
    }

}
