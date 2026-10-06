package net.cumba.corej.editor.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The one repository this instance is bound to (O9): its clone URL and the {@code owner/name} the
 * forge API addresses it by — the last two path segments of the URL, with a trailing {@code .git}
 * stripped. {@code https://forge.example.org/cumba/cumba-corej-rules.git} → {@code cumba} /
 * {@code cumba-corej-rules}; a {@code file://} URL (tests) parses the same way.
 *
 * @param url
 *            the clone URL exactly as configured
 * @param owner
 *            the forge owner (user or organisation)
 * @param name
 *            the repository name
 */
public record RepositoryCoordinates(String url, String owner, String name)
{

    /** {@link #parse(String, boolean, boolean)} with {@code https} only. */
    public static RepositoryCoordinates parse(String aUrl)
    {
        return parse(aUrl, false, false);
    }


    /**
     * Parses and checks the clone URL (security review L3): {@code https} only — {@code http} with
     * {@code editor.repository.allow-http} (a developer run), {@code file} with {@code allow-file}
     * (tests) — and never a user-info, query or fragment part. The URL is shown by
     * {@code /api/info} and used for every user's git operations, so a credential in it would leak
     * to everybody and authenticate everybody.
     *
     * @throws IllegalArgumentException
     *             on a missing, malformed or refused URL
     */
    public static RepositoryCoordinates parse(String aUrl, boolean aAllowHttp, boolean aAllowFile)
    {
        String url = aUrl.strip();
        if (url.isEmpty())
        {
            throw new IllegalArgumentException("editor.repository.url is not set");
        }
        URI uri = checked(url, "editor.repository.url", aAllowHttp, aAllowFile);
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/"))
        {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(".git"))
        {
            path = path.substring(0, path.length() - 4);
        }
        String[] segments = path.split("/", -1);
        if (segments.length < 3 || segments[segments.length - 1].isEmpty()
                || segments[segments.length - 2].isEmpty())
        {
            throw new IllegalArgumentException(
                    "editor.repository.url must end in /<owner>/<repository>[.git]: " + url);
        }
        return new RepositoryCoordinates(url, segments[segments.length - 2],
                segments[segments.length - 1]);
    }


    /**
     * Checks {@code editor.forge.api-url} the same way (security review L3): {@code https}, or
     * {@code http} with {@code allow-http}; no user-info, query or fragment. Answers it stripped.
     *
     * @throws IllegalArgumentException
     *             on a missing, malformed or refused URL
     */
    public static String checkApiUrl(String aUrl, boolean aAllowHttp)
    {
        String url = aUrl.strip();
        if (url.isEmpty())
        {
            throw new IllegalArgumentException("editor.forge.api-url is not set");
        }
        checked(url, "editor.forge.api-url", aAllowHttp, false);
        return url;
    }


    private static URI checked(String aUrl, String aKey, boolean aAllowHttp, boolean aAllowFile)
    {
        URI uri;
        try
        {
            uri = new URI(aUrl);
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException(
                    aKey + " is not a URL: " + aUrl + " (" + e.getMessage() + ")", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean allowed = "https".equals(scheme) || ("http".equals(scheme) && aAllowHttp)
                || ("file".equals(scheme) && aAllowFile);
        if (!allowed)
        {
            throw new IllegalArgumentException(aKey + " must be an https URL (the login token "
                    + "authenticates git and the forge API over it; http only with "
                    + "editor.repository.allow-http for a developer run): " + redacted(uri));
        }
        if (uri.getRawUserInfo() != null)
        {
            throw new IllegalArgumentException(
                    aKey + " must not carry credentials (user-info): " + redacted(uri));
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null)
        {
            throw new IllegalArgumentException(
                    aKey + " must not carry a query or a fragment: " + redacted(uri));
        }
        if (!"file".equals(scheme) && (uri.getHost() == null || uri.getHost().isEmpty()))
        {
            throw new IllegalArgumentException(aKey + " names no host: " + redacted(uri));
        }
        return uri;
    }


    /** The URL without its user-info, for a message: a refused credential is never echoed. */
    private static String redacted(URI aUri)
    {
        if (aUri.getRawUserInfo() == null)
        {
            return aUri.toString();
        }
        return aUri.getScheme() + "://***@" + aUri.getHost()
                + (aUri.getPort() >= 0 ? ":" + aUri.getPort() : "")
                + (aUri.getRawPath() == null ? "" : aUri.getRawPath());
    }


    /**
     * The forge's web base, {@code scheme://host[:port]}, for links back to it (the "create pull
     * request" page).
     */
    public String webBase()
    {
        URI uri = URI.create(url);
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() >= 0)
        {
            sb.append(':').append(uri.getPort());
        }
        return sb.toString();
    }
}
