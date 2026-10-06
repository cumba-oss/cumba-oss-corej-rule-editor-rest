package net.cumba.corej.editor.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The body limit (security review M2): a declared length past it is refused before the chain, an
 * undeclared (chunked) one is counted and cut off while it is read.
 */
class RequestSizeFilterTest
{

    /** A request that declares no length, like a chunked upload. */
    static MockHttpServletRequest chunked(byte[] aBody)
    {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/analyse")
        {

            @Override
            public long getContentLengthLong()
            {
                return -1;
            }


            @Override
            public int getContentLength()
            {
                return -1;
            }
        };
        r.setContent(aBody);
        return r;
    }


    @Test
    void aDeclaredLengthPastTheLimitIsRefusedBeforeTheChain() throws Exception
    {
        RequestSizeFilter f = new RequestSizeFilter(10);
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/analyse");
        r.setContent(new byte[11]);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicReference<ServletRequest> reached = new AtomicReference<>();
        f.doFilter(r, resp, (req, res) -> reached.set(req));
        assertEquals(413, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"code\":\"TOO_LARGE\""));
        assertEquals(null, reached.get(), "the chain never saw it");
    }


    @Test
    void anUndeclaredBodyIsCutOffWhileItIsRead() throws Exception
    {
        RequestSizeFilter f = new RequestSizeFilter(10);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        f.doFilter(chunked(new byte[20]), resp,
                (req, res) -> assertThrows(RequestSizeFilter.TooLarge.class,
                        () -> req.getInputStream().readAllBytes()));
        f.doFilter(chunked("x".repeat(20).getBytes(StandardCharsets.UTF_8)), resp,
                (req, res) -> assertThrows(IOException.class,
                        () -> req.getReader().transferTo(java.io.Writer.nullWriter())));
        f.doFilter(chunked(new byte[10]), resp,
                (req, res) -> assertEquals(10, req.getInputStream().readAllBytes().length));
        assertFalse(EditorExceptionHandler.causedByTooLarge(new IllegalStateException("x")));
        assertTrue(EditorExceptionHandler
                .causedByTooLarge(new IllegalStateException(new RequestSizeFilter.TooLarge(10))));
    }
}
