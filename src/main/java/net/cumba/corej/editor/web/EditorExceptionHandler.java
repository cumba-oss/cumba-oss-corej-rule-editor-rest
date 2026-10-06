package net.cumba.corej.editor.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import net.cumba.corej.editor.error.EditorException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders the editor's errors as RFC 9457 problems with a {@code code} property (the contract the
 * SPA switches on) and the exception's details as further properties.
 */
@RestControllerAdvice
public class EditorExceptionHandler
{

    private static final System.Logger LOG = System
            .getLogger(EditorExceptionHandler.class.getName());

    @ExceptionHandler(EditorException.class)
    public ResponseEntity<ProblemDetail> handleEditor(EditorException aException)
    {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(aException.status(),
                aException.getMessage());
        p.setProperty("code", aException.code());
        aException.details().forEach(p::setProperty);
        if (aException.status().is5xxServerError())
        {
            LOG.log(System.Logger.Level.WARNING, aException.getMessage(), aException);
        }
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
                "I/O failure: " + aException.getMessage());
        p.setProperty("code", "IO_FAILED");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(p);
    }
}
