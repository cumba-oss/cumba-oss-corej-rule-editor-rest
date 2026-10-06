package net.cumba.corej.editor.forge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import net.cumba.corej.editor.config.BuildInfo;
import net.cumba.corej.editor.config.RepositoryCoordinates;
import net.cumba.corej.editor.error.EditorException;
import net.cumba.corej.editor.error.Errors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the Gitea and GitHub clients share: one {@link HttpClient}, JSON GETs with the forge's
 * authorization header, and the two git smart-HTTP advertisement probes, which are forge-neutral.
 */
abstract class HttpForgeClient implements ForgeClient
{

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final String USER_AGENT = BuildInfo.ARTIFACT_ID;

    private final HttpClient http;

    private final JsonMapper json = JsonMapper.builder().build();

    private final String apiUrl;

    protected HttpForgeClient(String aApiUrl, HttpClient aHttp)
    {
        String api = aApiUrl.strip();
        if (api.isEmpty())
        {
            throw new IllegalArgumentException("editor.forge.api-url is not set");
        }
        while (api.endsWith("/"))
        {
            api = api.substring(0, api.length() - 1);
        }
        apiUrl = api;
        http = aHttp;
    }


    static HttpClient defaultHttpClient()
    {
        return HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }


    /** The API base without a trailing slash. */
    protected final String apiUrl()
    {
        return apiUrl;
    }


    /** The forge's {@code Authorization} header value for a token. */
    protected abstract String authorization(ForgeCredentials aCredentials);


    /** Extra headers every API request carries (GitHub's Accept / API version). */
    protected Map<String, String> apiHeaders()
    {
        return Map.of();
    }


    /**
     * {@code GET <api>/<path>} as JSON. 2xx → the document; 401 / 403 / 404 → {@code FORGE_REFUSED}
     * (Gitea reports a missing or invisible repository as 404 and never challenges, so all three
     * mean "this token cannot see it"); anything else → {@code FORGE_UNAVAILABLE}.
     */
    protected final JsonNode getJson(ForgeCredentials aCredentials, String aPath)
    {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl + aPath)).GET()
                .timeout(TIMEOUT).header("Authorization", authorization(aCredentials))
                .header("Accept", "application/json").header("User-Agent", USER_AGENT);
        apiHeaders().forEach(b::setHeader);
        HttpResponse<String> r = send(b.build(), "the forge API " + aPath);
        int status = r.statusCode();
        if (status == 401 || status == 403 || status == 404)
        {
            throw Errors.forgeRefused(
                    "the forge refused the token for " + aPath + " (HTTP " + status + ")");
        }
        if (status < 200 || status >= 300)
        {
            throw Errors
                    .forgeUnavailable("the forge API answered HTTP " + status + " for " + aPath);
        }
        try
        {
            return json.readTree(r.body());
        }
        catch (JacksonException e)
        {
            EditorException ex = Errors
                    .forgeUnavailable("the forge API answered non-JSON for " + aPath);
            ex.initCause(e);
            throw ex;
        }
    }


    @Override
    public boolean canReceivePack(ForgeCredentials aCredentials, RepositoryCoordinates aRepository)
    {
        HttpRequest req = HttpRequest.newBuilder(advertisement(aRepository, "git-receive-pack"))
                .GET().timeout(TIMEOUT).header("Authorization", aCredentials.basicAuthorization())
                .header("User-Agent", USER_AGENT).build();
        return send(req, "the receive-pack probe").statusCode() == 200;
    }


    @Override
    public boolean anonymouslyReadable(RepositoryCoordinates aRepository)
    {
        HttpRequest req = HttpRequest.newBuilder(advertisement(aRepository, "git-upload-pack"))
                .GET().timeout(TIMEOUT).header("User-Agent", USER_AGENT).build();
        try
        {
            return send(req, "the anonymous visibility probe").statusCode() == 200;
        }
        catch (EditorException e)
        {
            // an unreachable forge is "not visible" for the anonymous clone, not an error
            return false;
        }
    }


    private static URI advertisement(RepositoryCoordinates aRepository, String aService)
    {
        String base = aRepository.url();
        while (base.endsWith("/"))
        {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/info/refs?service=" + aService);
    }


    private HttpResponse<String> send(HttpRequest aRequest, String aWhat)
    {
        try
        {
            return http.send(aRequest, HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException e)
        {
            EditorException ex = Errors
                    .forgeUnavailable(aWhat + " did not answer: " + e.getMessage());
            ex.initCause(e);
            throw ex;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            EditorException ex = Errors.forgeUnavailable(aWhat + " was interrupted");
            ex.initCause(e);
            throw ex;
        }
    }


    /** A JSON string field, {@code ""} when absent or null. */
    protected static String text(JsonNode aNode, String aField)
    {
        JsonNode n = aNode.path(aField);
        return n.isNull() || n.isMissingNode() ? "" : n.asString("");
    }
}
