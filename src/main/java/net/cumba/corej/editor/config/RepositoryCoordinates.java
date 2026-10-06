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

    public static RepositoryCoordinates parse(String aUrl)
    {
        String url = aUrl.strip();
        if (url.isEmpty())
        {
            throw new IllegalArgumentException("editor.repository.url is not set");
        }
        URI uri;
        try
        {
            uri = new URI(url);
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException(
                    "editor.repository.url is not a URL: " + url + " (" + e.getMessage() + ")", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme) && !"file".equals(scheme))
        {
            throw new IllegalArgumentException("editor.repository.url must be an http(s) "
                    + "clone URL (the login token authenticates git over HTTPS): " + url);
        }
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
