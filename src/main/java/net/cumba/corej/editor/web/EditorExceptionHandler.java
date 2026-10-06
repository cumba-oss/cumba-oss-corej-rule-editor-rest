package net.cumba.corej.editor.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.error.EditorException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as an RFC 9457 problem with a {@code code} property (the contract the SPA
 * switches on) and the exception's details as further properties — the editor's own errors, and
 * Spring MVC's (an unreadable body, a missing or mistyped parameter, 404 / 405 / 415 …) too (REST
 * review #9: those carried no {@code code}). A body cut off by {@link RequestSizeFilter} is
 * {@code 413 TOO_LARGE}.
 *
 * <p>
 * No server path reaches a client (security review L8): an I/O failure answers a generic text (the
 * log has the exception), and a 5xx message has the workspace root replaced by
 * {@code <workspaces>}.
 * </p>
 */
@RestControllerAdvice
public class EditorExceptionHandler extends ResponseEntityExceptionHandler
{

    private static final System.Logger LOG = System
            .getLogger(EditorExceptionHandler.class.getName());

    static final String WORKSPACES = "<workspaces>";

    private final @Nullable String workspaceRoot;

    @Autowired
    public EditorExceptionHandler(EditorProperties aProperties)
    {
        this(aProperties.workspaceRootPath());
    }


    EditorExceptionHandler(@Nullable Path aWorkspaceRoot)
    {
        workspaceRoot = aWorkspaceRoot == null ? null : aWorkspaceRoot.toString();
    }


    @ExceptionHandler(EditorException.class)
    public ResponseEntity<ProblemDetail> handleEditor(EditorException aException)
    {
        String message = String.valueOf(aException.getMessage());
        if (aException.status().is5xxServerError())
        {
            LOG.log(System.Logger.Level.WARNING, message, aException);
            message = scrub(message);
        }
        ProblemDetail p = ProblemDetail.forStatusAndDetail(aException.status(), message);
        p.setProperty("code", aException.code());
        aException.details().forEach(p::setProperty);
        return ResponseEntity.status(aException.status()).body(p);
    }


    @ExceptionHandler(
    {
            IOException.class, UncheckedIOException.class
    })
    public ResponseEntity<ProblemDetail> handleIo(Exception aException)
    {
        LOG.log(System.Logger.Level.WARNING, "I/O failure", aException);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "an I/O operation of the server failed — the server log has the details");
        p.setProperty("code", "IO_FAILED");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(p);
    }


    /** Anything else a handler throws: 500 with a code, the message in the log only. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(RuntimeException aException)
    {
        LOG.log(System.Logger.Level.ERROR, "unexpected failure", aException);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "the server failed unexpectedly — the server log has the details");
        p.setProperty("code", "INTERNAL");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(p);
    }


    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException aException, HttpHeaders aHeaders,
            HttpStatusCode aStatus, WebRequest aRequest)
    {
        if (causedByTooLarge(aException))
        {
            ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                    "the request body exceeds the server's limit");
            p.setProperty("code", "TOO_LARGE");
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(p);
        }
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "the request body is not the JSON this endpoint reads");
        p.setProperty("code", "BAD_REQUEST");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(aHeaders).body(p);
    }


    static boolean causedByTooLarge(Throwable aException)
    {
        for (Throwable t = aException; t != null; t = t.getCause())
        {
            if (t instanceof RequestSizeFilter.TooLarge)
            {
                return true;
            }
            if (t.getCause() == t)
            {
                break;
            }
        }
        return false;
    }


    /** Spring MVC's own problems get the {@code code} the SPA switches on. */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception aException,
            @Nullable Object aBody, HttpHeaders aHeaders, HttpStatusCode aStatus,
            WebRequest aRequest)
    {
        ResponseEntity<Object> entity = super.handleExceptionInternal(aException, aBody, aHeaders,
                aStatus, aRequest);
        if (entity != null && entity.getBody() instanceof ProblemDetail p
                && (p.getProperties() == null || !p.getProperties().containsKey("code")))
        {
            p.setProperty("code", codeOf(aStatus));
        }
        return entity;
    }


    static String codeOf(HttpStatusCode aStatus)
    {
        return switch (aStatus.value())
        {
        case 400 -> "BAD_REQUEST";
        case 404 -> "NOT_FOUND";
        case 405 -> "METHOD_NOT_ALLOWED";
        case 406 -> "NOT_ACCEPTABLE";
        case 413 -> "TOO_LARGE";
        case 415 -> "UNSUPPORTED_MEDIA_TYPE";
        case 503 -> "UNAVAILABLE";
        default -> aStatus.is5xxServerError() ? "INTERNAL" : "HTTP_" + aStatus.value();
        };
    }


    /** The message with the workspace root replaced, so no absolute server path leaks. */
    String scrub(String aMessage)
    {
        String root = workspaceRoot;
        return root == null || root.isEmpty() ? aMessage : aMessage.replace(root, WORKSPACES);
    }
}
