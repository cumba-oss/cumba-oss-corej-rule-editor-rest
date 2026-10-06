package net.cumba.corej.editor.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A JDK {@link HttpServer} playing a forge's API and its git smart-HTTP endpoints: each path is
 * answered by a function of the exchange, and every request is recorded (method, path, headers) so
 * a test can assert what the client sent.
 */
public final class FakeForgeServer implements AutoCloseable
{

    /** One answer. */
    public record Reply(int status, String body)
    {
    }


    /** One recorded request. */
    public record Seen(String method, String path, Map<String, List<String>> headers)
    {

        public String header(String aName)
        {
            List<String> v = headers.get(aName);
            return v == null || v.isEmpty() ? "" : v.get(0);
        }
    }

    private final HttpServer server;

    private final Map<String, Function<HttpExchange, Reply>> routes = new ConcurrentHashMap<>();

    private final List<Seen> seen = new ArrayList<>();

    public FakeForgeServer() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }


    /** {@code http://127.0.0.1:<port>}. */
    public String baseUrl()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }


    /** Routes an exact path (query string included) to a constant reply. */
    public FakeForgeServer on(String aPathAndQuery, int aStatus, String aBody)
    {
        routes.put(aPathAndQuery, x -> new Reply(aStatus, aBody));
        return this;
    }


    /** Routes an exact path to a function of the exchange (to inspect headers). */
    public FakeForgeServer on(String aPathAndQuery, Function<HttpExchange, Reply> aReply)
    {
        routes.put(aPathAndQuery, aReply);
        return this;
    }


    public synchronized List<Seen> seen()
    {
        return List.copyOf(seen);
    }


    private void handle(HttpExchange aExchange) throws IOException
    {
        String path = aExchange.getRequestURI().getPath();
        String query = aExchange.getRequestURI().getRawQuery();
        String key = query == null ? path : path + "?" + query;
        synchronized (this)
        {
            seen.add(new Seen(aExchange.getRequestMethod(), key,
                    Map.copyOf(aExchange.getRequestHeaders())));
        }
        Function<HttpExchange, Reply> route = routes.get(key);
        Reply reply = route == null ? new Reply(404, "{\"message\":\"not found\"}")
                : route.apply(aExchange);
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        aExchange.getResponseHeaders().add("Content-Type", "application/json");
        aExchange.sendResponseHeaders(reply.status(), body.length);
        try (OutputStream out = aExchange.getResponseBody())
        {
            out.write(body);
        }
    }


    @Override
    public void close()
    {
        server.stop(0);
    }
}
