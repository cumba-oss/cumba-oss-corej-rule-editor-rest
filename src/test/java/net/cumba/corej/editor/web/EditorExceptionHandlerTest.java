package net.cumba.corej.editor.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Path;
import net.cumba.corej.editor.error.Errors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** No server path reaches a client (security review L8), and every problem has a code (#9). */
class EditorExceptionHandlerTest
{

    @Test
    void serverPathsNeverReachAClient()
    {
        Path root = Path.of("/var/lib/rule-editor/workspaces").toAbsolutePath();
        EditorExceptionHandler h = new EditorExceptionHandler(root);
        var git = h.handleEditor(Errors.git("git commit failed",
                new IOException("Cannot lock " + root.resolve("8/.git/index"))));
        String detail = String.valueOf(git.getBody().getDetail());
        assertFalse(detail.contains(root.toString()), detail);
        assertEquals("git commit failed: Cannot lock " + EditorExceptionHandler.WORKSPACES
                + "/8/.git/index".replace('/', java.io.File.separatorChar), detail);
        var io = h.handleIo(new IOException("/etc/rule-editor/secret: denied"));
        assertFalse(String.valueOf(io.getBody().getDetail()).contains("/etc"),
                "an I/O failure answers a generic text");
        assertEquals("IO_FAILED", io.getBody().getProperties().get("code"));
        var unexpected = h.handleUnexpected(new IllegalStateException(root + " broke"));
        assertEquals("INTERNAL", unexpected.getBody().getProperties().get("code"));
        assertFalse(String.valueOf(unexpected.getBody().getDetail()).contains(root.toString()));
    }


    @Test
    void springStatusesMapToCodes()
    {
        assertEquals("BAD_REQUEST", EditorExceptionHandler.codeOf(HttpStatus.BAD_REQUEST));
        assertEquals("NOT_FOUND", EditorExceptionHandler.codeOf(HttpStatus.NOT_FOUND));
        assertEquals("METHOD_NOT_ALLOWED",
                EditorExceptionHandler.codeOf(HttpStatus.METHOD_NOT_ALLOWED));
        assertEquals("UNSUPPORTED_MEDIA_TYPE",
                EditorExceptionHandler.codeOf(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        assertEquals("TOO_LARGE", EditorExceptionHandler.codeOf(HttpStatus.CONTENT_TOO_LARGE));
        assertEquals("INTERNAL", EditorExceptionHandler.codeOf(HttpStatus.BAD_GATEWAY));
        assertEquals("HTTP_409", EditorExceptionHandler.codeOf(HttpStatus.CONFLICT));
    }
}
