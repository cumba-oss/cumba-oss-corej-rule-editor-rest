package net.cumba.corej.editor.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.cumba.corej.editor.error.Errors;
import net.cumba.corej.editor.git.PathPolicy;
import net.cumba.corej.editor.git.Workspace;
import net.cumba.corej.editor.session.SessionUser;
import net.cumba.corej.editor.web.dto.Dtos;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Files under the allow-list (§5.8.2): one directory level, a file's content with its blob id, and
 * the write that Save performs — refused in read-only mode (403), on the default branch (409), and
 * when {@code {branch, head, blob}} no longer hold (409 {@code STALE}).
 */
@RestController
@RequestMapping("/api/files")
@Tag(name = "files", description = "Read and write files under rules-src and the scenario tree")
public class FileController
{

    private final Access access;

    public FileController(Access aAccess)
    {
        access = aAccess;
    }


    @GetMapping
    @Operation(summary = "List one directory level under the editable trees")
    public Dtos.Directory list(@AuthenticationPrincipal @Nullable SessionUser aUser,
            @RequestParam("path") String aPath, HttpServletRequest aRequest)
        throws IOException
    {
        String path = PathPolicy.check(aPath);
        return access.read(aUser, aRequest,
                v -> new Dtos.Directory(path, v.source().list(path), v.branch(), v.head()));
    }


    @GetMapping("/content")
    @Operation(summary = "A file's content (UTF-8) with its blob id, branch and HEAD")
    public Dtos.FileContent read(@AuthenticationPrincipal @Nullable SessionUser aUser,
            @RequestParam("path") String aPath, HttpServletRequest aRequest)
        throws IOException
    {
        String path = PathPolicy.check(aPath);
        return access.read(aUser, aRequest, v ->
        {
            byte[] bytes = v.source().read(path).orElseThrow(() -> Errors.notFound(path));
            return new Dtos.FileContent(path, new String(bytes, StandardCharsets.UTF_8),
                    Workspace.blobId(bytes), v.branch(), v.head());
        });
    }


    @PutMapping("/content")
    @Operation(summary = "Save: write a file's content if the client's blob is still current")
    public Dtos.Written write(@AuthenticationPrincipal SessionUser aUser,
            @RequestBody Dtos.WriteFile aBody, HttpServletRequest aRequest)
        throws IOException
    {
        aUser.requireEditMode();
        if (aBody.path() == null || aBody.content() == null)
        {
            throw Errors.badRequest("path and content are required");
        }
        String path = PathPolicy.check(aBody.path());
        return access.workspace(aUser, aRequest, (ws, c) ->
        {
            ws.requireExpected(aBody.branch(), aBody.head());
            String blob = ws.write(path, aBody.content().getBytes(StandardCharsets.UTF_8),
                    aBody.blob());
            return new Dtos.Written(path, blob, ws.currentBranch(), ws.head().name());
        });
    }
}
