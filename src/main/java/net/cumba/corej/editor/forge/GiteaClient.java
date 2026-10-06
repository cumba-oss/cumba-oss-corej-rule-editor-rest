package net.cumba.corej.editor.forge;

import java.net.URI;
import java.net.http.HttpClient;
import net.cumba.corej.editor.config.RepositoryCoordinates;
import net.cumba.corej.editor.error.Errors;
import tools.jackson.databind.JsonNode;

/**
 * Gitea over its {@code /api/v1}. Measured against the live Gitea on 2026-10-06 (phase 0):
 * {@code GET /user} answers {@code {id, login, full_name, email}} with {@code full_name} possibly
 * empty; {@code GET /repos/{owner}/{repo}} answers {@code permissions {admin, push, pull}}; an
 * unknown or invisible repository is 404, never 401.
 */
public final class GiteaClient extends HttpForgeClient
{

    public GiteaClient(String aApiUrl)
    {
        this(aApiUrl, defaultHttpClient());
    }


    GiteaClient(String aApiUrl, HttpClient aHttp)
    {
        super(aApiUrl, aHttp);
    }


    @Override
    protected String authorization(ForgeCredentials aCredentials)
    {
        return "token " + aCredentials.token();
    }


    @Override
    public ForgeUser user(ForgeCredentials aCredentials)
    {
        JsonNode u = getJson(aCredentials, "/user");
        if (!u.path("id").isNumber())
        {
            throw Errors.forgeUnavailable("GET /user answered without a numeric id");
        }
        String login = text(u, "login");
        String email = text(u, "email");
        if (email.isBlank())
        {
            // Gitea reports the token owner's email; a hidden one gets Gitea's own noreply shape
            email = login + "@noreply." + URI.create(apiUrl()).getHost();
        }
        return new ForgeUser(u.path("id").asLong(), login, text(u, "full_name"), email);
    }


    @Override
    public RepositoryAccess repository(ForgeCredentials aCredentials,
            RepositoryCoordinates aRepository)
    {
        JsonNode r = getJson(aCredentials,
                "/repos/" + aRepository.owner() + "/" + aRepository.name());
        JsonNode p = r.path("permissions");
        String defaultBranch = text(r, "default_branch");
        if (defaultBranch.isBlank())
        {
            throw Errors.forgeUnavailable("the forge reported no default branch for "
                    + aRepository.owner() + "/" + aRepository.name());
        }
        return new RepositoryAccess(p.path("pull").asBoolean(false),
                p.path("push").asBoolean(false), defaultBranch, r.path("private").asBoolean(false));
    }


    @Override
    public String pullRequestUrl(RepositoryCoordinates aRepository, String aDefaultBranch,
            String aBranch)
    {
        return aRepository.webBase() + "/" + aRepository.owner() + "/" + aRepository.name()
                + "/compare/" + aDefaultBranch + "..." + aBranch;
    }
}
