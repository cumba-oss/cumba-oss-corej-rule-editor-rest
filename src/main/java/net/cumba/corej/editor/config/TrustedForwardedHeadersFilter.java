package net.cumba.corej.editor.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import net.cumba.corej.editor.web.ClientAddress;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ForwardedHeaderUtils;
import org.springframework.web.util.UriComponents;

/**
 * The scheme, host and port the BROWSER used, behind a TLS-terminating proxy
 * (PLAN-rule-editor-forwarded-headers, FH1 ⓐ): the browser sends {@code Origin: https://host}, the
 * proxy talks plain HTTP to the editor, and without the forwarded scheme Spring's CORS check took
 * every request carrying an {@code Origin} — the login, every save — for a cross-origin one and
 * refused it (403 {@code Invalid CORS request}).
 * <ul>
 * <li>Believed only from a TCP peer in {@code editor.trusted-proxies}
 * ({@link ClientAddress#trustedPeer}), the trust list of the per-client limits: one setting for
 * both. From any other peer the request passes through unchanged.</li>
 * <li>Only {@code X-Forwarded-Proto}, {@code X-Forwarded-Host} and {@code X-Forwarded-Port} are
 * read; {@code Forwarded}, {@code X-Forwarded-For}, {@code X-Forwarded-Ssl} and
 * {@code X-Forwarded-Prefix} never are.</li>
 * <li>The wrapper overrides {@code getScheme}, {@code isSecure}, {@code getServerName},
 * {@code getServerPort} and {@code getRequestURL} and nothing else: the headers and
 * {@code getRemoteAddr()} stay as they arrived, so {@link ClientAddress#key} still walks
 * {@code X-Forwarded-For} from the real peer. ⛔ Never Spring's {@code ForwardedHeaderFilter}: it
 * makes the LEFT-most {@code X-Forwarded-For} / {@code Forwarded: for=} entry — the client's own
 * claim — the remote address, a fresh rate-limit bucket per request (security review M4).</li>
 * <li>The values are Spring's {@code ForwardedHeaderUtils} reading of those three headers, with one
 * correction: with neither a forwarded host nor a forwarded port, an explicit port in {@code Host}
 * is kept ({@code http://localhost:8080} through the SPA's nginx, a TLS proxy on {@code :8443}),
 * where Spring drops it on {@code X-Forwarded-Proto}.</li>
 * <li>Forwarded values from a trusted proxy that do not parse (a port that is no number or out of
 * range) answer {@code 400 BAD_REQUEST}, as Spring's own filter does.</li>
 * </ul>
 * Registered before the security chain, as {@code RequestSizeFilter} is.
 * {@code server.forward-headers-strategy} is pinned to {@code none} so that no second, unconfigured
 * trust path (Boot's RemoteIpValve on a detected cloud platform) exists beside this one.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TrustedForwardedHeadersFilter extends OncePerRequestFilter
{

    static final String FORWARDED_PROTO = "X-Forwarded-Proto";

    static final String FORWARDED_HOST = "X-Forwarded-Host";

    static final String FORWARDED_PORT = "X-Forwarded-Port";

    /** The only forwarding headers read, and only from a trusted peer. */
    static final List<String> FORWARDED = List.of(FORWARDED_PROTO, FORWARDED_HOST, FORWARDED_PORT);

    private static final int MAX_PORT = 65_535;

    private final ClientAddress clients;

    @Autowired
    public TrustedForwardedHeadersFilter(ClientAddress aClients)
    {
        clients = aClients;
    }


    @Override
    protected boolean shouldNotFilterAsyncDispatch()
    {
        return false;
    }


    @Override
    protected boolean shouldNotFilterErrorDispatch()
    {
        return false;
    }


    @Override
    protected void doFilterInternal(HttpServletRequest aRequest, HttpServletResponse aResponse,
            FilterChain aChain)
        throws ServletException, IOException
    {
        if (!clients.trustedPeer(aRequest))
        {
            aChain.doFilter(aRequest, aResponse);
            return;
        }
        HttpHeaders forwarded = new HttpHeaders();
        for (String name : FORWARDED)
        {
            Enumeration<String> values = aRequest.getHeaders(name);
            while (values != null && values.hasMoreElements())
            {
                forwarded.add(name, values.nextElement());
            }
        }
        if (forwarded.isEmpty())
        {
            aChain.doFilter(aRequest, aResponse);
            return;
        }
        Forwarded wrapped;
        try
        {
            wrapped = new Forwarded(aRequest, forwarded);
        }
        catch (IllegalArgumentException | IllegalStateException e)
        {
            aResponse.setStatus(HttpStatus.BAD_REQUEST.value());
            aResponse.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            aResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
            aResponse.getWriter()
                    .write("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                            + "\"detail\":\"the X-Forwarded-Proto/-Host/-Port of a trusted proxy "
                            + "do not parse\",\"code\":\"BAD_REQUEST\"}");
            return;
        }
        aChain.doFilter(wrapped, aResponse);
    }

    /** The request with the scheme, host and port the trusted proxy forwarded. */
    static final class Forwarded extends HttpServletRequestWrapper
    {

        private final String scheme;

        private final boolean secure;

        private final String host;

        private final int port;

        /**
         * @param aForwarded
         *            ONLY the {@link #FORWARDED} headers, copied from the request
         * @throws IllegalArgumentException
         *             when they do not parse
         */
        Forwarded(HttpServletRequest aRequest, HttpHeaders aForwarded)
        {
            super(aRequest);
            UriComponents uri = ForwardedHeaderUtils.adaptFromForwardedHeaders(
                    ServletServerHttpRequest.initURI(aRequest), aForwarded).build();
            String s = uri.getScheme();
            String h = uri.getHost();
            if (s == null || s.isBlank() || h == null || h.isBlank())
            {
                throw new IllegalArgumentException("no scheme or host");
            }
            scheme = s.toLowerCase(Locale.ROOT);
            secure = "https".equals(scheme);
            host = h;
            int p = uri.getPort();
            if (p == -1 && !StringUtils.hasText(aForwarded.getFirst(FORWARDED_HOST))
                    && !StringUtils.hasText(aForwarded.getFirst(FORWARDED_PORT)))
            {
                // ForwardedHeaderUtils drops the port on X-Forwarded-Proto: it takes Host for the
                // proxy's own listener. Here Host is the BROWSER's (the SPA's nginx forwards
                // $http_host, a TLS proxy keeps it), so its explicit port is the browser's port:
                // http://localhost:8080 stays :8080, https://host:8443 stays :8443
                p = explicitPort(aRequest.getHeader(HttpHeaders.HOST));
            }
            if (p == -1)
            {
                port = secure ? 443 : 80;
            }
            else if (p < 1 || p > MAX_PORT)
            {
                throw new IllegalArgumentException("port out of range: " + p);
            }
            else
            {
                port = p;
            }
        }


        /** The port a {@code Host} value names explicitly; -1 when it names none. */
        static int explicitPort(@Nullable String aHost)
        {
            if (aHost == null)
            {
                return -1;
            }
            String host = aHost.strip();
            int colon = host.lastIndexOf(':');
            if (colon < 0 || colon < host.lastIndexOf(']') || colon == host.length() - 1)
            {
                return -1;
            }
            if (host.indexOf(':') != colon && host.indexOf(']') < 0)
            {
                return -1; // an unbracketed IPv6 literal: no port
            }
            return Integer.parseInt(host, colon + 1, host.length(), 10);
        }


        @Override
        public String getScheme()
        {
            return scheme;
        }


        @Override
        public boolean isSecure()
        {
            return secure;
        }


        @Override
        public String getServerName()
        {
            return host;
        }


        @Override
        public int getServerPort()
        {
            return port;
        }


        @Override
        public StringBuffer getRequestURL()
        {
            StringBuffer url = new StringBuffer(scheme).append("://").append(host);
            if (port != (secure ? 443 : 80))
            {
                url.append(':').append(port);
            }
            return url.append(getRequestURI());
        }
    }
}
