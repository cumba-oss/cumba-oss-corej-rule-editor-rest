package net.cumba.corej.editor.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import net.cumba.corej.editor.config.EditorProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The request body limit (security review M2), in front of everything else: a request declaring
 * more than {@code editor.limits.max-request-bytes} (default four buffers' worth) is answered
 * {@code 413 TOO_LARGE} before anyone reads it, and a body without a declared length (chunked) is
 * counted while it is read and cut off at the limit ({@link TooLarge}, rendered 413 by
 * {@link EditorExceptionHandler}). The per-buffer limit ({@code max-buffer-bytes}) stays the
 * endpoints' own, finer check; this one bounds what Jackson deserialises at all.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeFilter extends OncePerRequestFilter
{

    /** Thrown by the counting stream when a body passes the limit. */
    public static final class TooLarge extends IOException
    {

        private static final long serialVersionUID = 1L;

        TooLarge(long aLimit)
        {
            super("the request body exceeds " + aLimit + " bytes");
        }
    }

    private final long limit;

    @Autowired
    public RequestSizeFilter(EditorProperties aProperties)
    {
        this(aProperties.getLimits().effectiveMaxRequestBytes());
    }


    RequestSizeFilter(long aLimit)
    {
        limit = aLimit;
    }


    /** The limit in force, in bytes. */
    public long limit()
    {
        return limit;
    }


    @Override
    protected void doFilterInternal(HttpServletRequest aRequest, HttpServletResponse aResponse,
            FilterChain aChain)
        throws ServletException, IOException
    {
        long declared = aRequest.getContentLengthLong();
        if (declared > limit)
        {
            aResponse.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            aResponse.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            aResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
            aResponse.getWriter().write(
                    "{\"type\":\"about:blank\",\"title\":\"Content Too Large\",\"status\":413,"
                            + "\"detail\":\"the request body declares " + declared
                            + " bytes; the limit is " + limit + "\",\"code\":\"TOO_LARGE\"}");
            return;
        }
        aChain.doFilter(new Counted(aRequest, limit), aResponse);
    }

    /** The request with its body counted. */
    static final class Counted extends HttpServletRequestWrapper
    {

        private final long limit;

        private @Nullable ServletInputStream stream;

        private @Nullable BufferedReader reader;

        Counted(HttpServletRequest aRequest, long aLimit)
        {
            super(aRequest);
            limit = aLimit;
        }


        @Override
        public ServletInputStream getInputStream() throws IOException
        {
            ServletInputStream s = stream;
            if (s == null)
            {
                s = new CountingStream(super.getInputStream(), limit);
                stream = s;
            }
            return s;
        }


        @Override
        public BufferedReader getReader() throws IOException
        {
            BufferedReader r = reader;
            if (r == null)
            {
                String encoding = getCharacterEncoding();
                Charset charset;
                try
                {
                    charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                }
                catch (IllegalArgumentException e)
                {
                    UnsupportedEncodingException bad = new UnsupportedEncodingException(encoding);
                    bad.initCause(e);
                    throw bad;
                }
                r = new BufferedReader(new InputStreamReader(getInputStream(), charset));
                reader = r;
            }
            return r;
        }
    }


    /** Counts the bytes read; past the limit every read throws {@link TooLarge}. */
    static final class CountingStream extends ServletInputStream
    {

        private final ServletInputStream in;

        private final long limit;

        private long count;

        CountingStream(ServletInputStream aIn, long aLimit)
        {
            in = aIn;
            limit = aLimit;
        }


        private void add(long aRead) throws TooLarge
        {
            if (aRead > 0)
            {
                count += aRead;
                if (count > limit)
                {
                    throw new TooLarge(limit);
                }
            }
        }


        @Override
        public int read() throws IOException
        {
            int b = in.read();
            if (b >= 0)
            {
                add(1);
            }
            return b;
        }


        @Override
        public int read(byte[] aBuffer, int aOffset, int aLength) throws IOException
        {
            int n = in.read(aBuffer, aOffset, aLength);
            add(n);
            return n;
        }


        @Override
        public boolean isFinished()
        {
            return in.isFinished();
        }


        @Override
        public boolean isReady()
        {
            return in.isReady();
        }


        @Override
        public void setReadListener(ReadListener aListener)
        {
            in.setReadListener(aListener);
        }


        @Override
        public void close() throws IOException
        {
            in.close();
        }
    }
}
