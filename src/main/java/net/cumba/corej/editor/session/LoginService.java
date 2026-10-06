package net.cumba.corej.editor.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.config.RepositoryCoordinates;
import net.cumba.corej.editor.error.Errors;
import net.cumba.corej.editor.forge.ForgeClient;
import net.cumba.corej.editor.forge.ForgeCredentials;
import net.cumba.corej.editor.forge.ForgeUser;
import net.cumba.corej.editor.forge.RepositoryAccess;
import net.cumba.corej.editor.git.WorkspaceService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

/**
 * Login and logout (§5.1.2–5.1.5). The probe: {@code GET /user} → identity; {@code GET /repos/…} →
 * {@code pull} or refused; {@code push} AND a successful receive-pack advertisement → edit mode
 * (review M4), else read-only. Then: session id rotated, the token stored as a transient session
 * attribute, the principal saved in the security context, and the workspace cloned with the user's
 * own token if it does not exist yet.
 */
@Service
public class LoginService
{

    public static final String ROLE_EDIT = "ROLE_EDIT";

    public static final String ROLE_READ = "ROLE_READ";

    private final ForgeClient forge;

    private final RepositoryCoordinates repository;

    private final EditorProperties properties;

    private final WorkspaceService workspaces;

    private final SecurityContextRepository contexts;

    private final SecurityContextHolderStrategy holder = SecurityContextHolder
            .getContextHolderStrategy();

    public LoginService(ForgeClient aForge, EditorProperties aProperties,
            WorkspaceService aWorkspaces, SecurityContextRepository aContexts)
    {
        forge = aForge;
        repository = aProperties.coordinates();
        properties = aProperties;
        workspaces = aWorkspaces;
        contexts = aContexts;
    }


    /**
     * Probes the forge, establishes the session and prepares the workspace.
     *
     * @throws net.cumba.corej.editor.error.EditorException
     *             401 {@code FORGE_REFUSED}, 502 {@code FORGE_UNAVAILABLE}
     */
    public SessionUser login(ForgeCredentials aCredentials, HttpServletRequest aRequest,
            HttpServletResponse aResponse)
    {
        ForgeUser user = forge.user(aCredentials);
        RepositoryAccess access = forge.repository(aCredentials, repository);
        if (!access.pull())
        {
            throw Errors.forgeRefused(
                    "the token cannot read " + repository.owner() + "/" + repository.name());
        }
        boolean write = access.push() && forge.canReceivePack(aCredentials, repository);
        String defaultBranch = properties.getRepository().configuredDefaultBranch()
                .orElse(access.defaultBranch());
        SessionUser principal = new SessionUser(user.id(), user.login(), user.name(), user.email(),
                write ? AccessMode.EDIT : AccessMode.READ_ONLY, defaultBranch);

        SessionCredentials credentials = new SessionCredentials(aCredentials);
        // the clone first: a failed clone leaves no half-established session behind
        workspaces.workspace(principal.id(), defaultBranch, principal.name(), principal.email(),
                credentials.git());

        HttpSession existing = aRequest.getSession(false);
        if (existing != null)
        {
            aRequest.changeSessionId();
        }
        HttpSession session = aRequest.getSession(true);
        session.setAttribute(SessionCredentials.ATTRIBUTE, credentials);
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority(write ? ROLE_EDIT : ROLE_READ)));
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(auth);
        holder.setContext(context);
        contexts.saveContext(context, aRequest, aResponse);
        return principal;
    }


    /** Invalidates the session (drops the token) and clears the security context. */
    public void logout(HttpServletRequest aRequest, HttpServletResponse aResponse)
    {
        SecurityContextLogoutHandler handler = new SecurityContextLogoutHandler();
        handler.setSecurityContextRepository(contexts);
        handler.logout(aRequest, aResponse, holder.getContext().getAuthentication());
    }


    /** The session's credentials; 401 when the session carries none. */
    public static SessionCredentials credentials(HttpServletRequest aRequest)
    {
        HttpSession session = aRequest.getSession(false);
        Object c = session == null ? null : session.getAttribute(SessionCredentials.ATTRIBUTE);
        if (c instanceof SessionCredentials sc)
        {
            return sc;
        }
        throw Errors.unauthenticated("no login in this session");
    }
}
