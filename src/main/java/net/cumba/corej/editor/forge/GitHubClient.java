package net.cumba.corej.editor.forge;

import java.net.http.HttpClient;
import java.util.Map;
import net.cumba.corej.editor.config.RepositoryCoordinates;
import net.cumba.corej.editor.error.EditorException;
import net.cumba.corej.editor.error.Errors;
import tools.jackson.databind.JsonNode;

/**
 * GitHub over {@code https://api.github.com} — by documentation only (phase 0 had no GitHub token):
 * {@code GET /user} may answer {@code email: null}, then {@code GET /user/emails} yields the
 * primary verified address, else the {@code <id>+<login>@users.noreply.github.com} form. {@code GET
 * /repos/{owner}/{repo}} answers the same {@code permissions} shape as Gitea.
 */
public final class GitHubClient extends HttpForgeClient
{

    public GitHubClient(String aApiUrl)
    {
        this(aApiUrl, defaultHttpClient());
    }


    GitHubClient(String aApiUrl, HttpClient aHttp)
    {
        super(aApiUrl, aHttp);
    }


    @Override
    protected String authorization(ForgeCredentials aCredentials)
    {
        return "Bearer " + aCredentials.token();
    }


    @Override
    protected Map<String, String> apiHeaders()
    {
        return Map.of("Accept", "application/vnd.github+json", "X-GitHub-Api-Version",
                "2022-11-28");
    }


    @Override
    public ForgeUser user(ForgeCredentials aCredentials)
    {
        JsonNode u = getJson(aCredentials, "/user");
        if (!u.path("id").isNumber())
        {
            throw Errors.forgeUnavailable("GET /user answered without a numeric id");
        }
        long id = u.path("id").asLong();
        String login = text(u, "login");
        String email = text(u, "email");
        if (email.isBlank())
        {
            email = primaryVerifiedEmail(aCredentials);
        }
        if (email.isBlank())
        {
            email = id + "+" + login + "@users.noreply.github.com";
        }
        return new ForgeUser(id, login, text(u, "name"), email);
    }


    private String primaryVerifiedEmail(ForgeCredentials aCredentials)
    {
        JsonNode emails;
        try
        {
            emails = getJson(aCredentials, "/user/emails");
        }
        catch (EditorException e)
        {
            // a token without the user:email scope cannot list them: fall through to noreply
            return "";
        }
        String first = "";
        for (JsonNode e : emails)
        {
            String address = text(e, "email");
            if (address.isBlank() || !e.path("verified").asBoolean(false))
            {
                continue;
            }
            if (e.path("primary").asBoolean(false))
            {
                return address;
            }
            if (first.isEmpty())
            {
                first = address;
            }
        }
        return first;
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
                + "/compare/" + aDefaultBranch + "..." + aBranch + "?expand=1";
    }
}
