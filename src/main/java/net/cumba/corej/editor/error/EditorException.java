package net.cumba.corej.editor.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The base of every error the editor answers deliberately: an HTTP status, a stable
 * machine-readable {@code code} the SPA switches on, a human message, and optional details (the
 * paths of a dirty tree, the conflicting paths of a merge, the current HEAD of a stale request).
 * Rendered as an RFC 9457 problem by {@code EditorExceptionHandler}.
 *
 * <p>
 * The codes are part of the API contract (see the README's endpoint table). A new situation gets a
 * new code, never a reused one.
 * </p>
 */
public class EditorException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    private final String code;

    private final transient Map<String, Object> details;

    public EditorException(HttpStatus aStatus, String aCode, String aMessage)
    {
        this(aStatus, aCode, aMessage, Map.of());
    }


    public EditorException(HttpStatus aStatus, String aCode, String aMessage,
            Map<String, Object> aDetails)
    {
        super(aMessage);
        status = aStatus;
        code = aCode;
        details = new LinkedHashMap<>(aDetails);
    }


    public HttpStatus status()
    {
        return status;
    }


    public String code()
    {
        return code;
    }


    /** Extra problem properties, in declaration order; never null. */
    public Map<String, Object> details()
    {
        return Map.copyOf(details);
    }
}
