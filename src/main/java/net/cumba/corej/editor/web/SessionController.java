package net.cumba.corej.editor.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.error.EditorException;
import net.cumba.corej.editor.error.Errors;
import net.cumba.corej.editor.forge.ForgeCredentials;
import net.cumba.corej.editor.run.RateLimiter;
import net.cumba.corej.editor.session.LoginService;
import net.cumba.corej.editor.session.SessionCredentials;
import net.cumba.corej.editor.session.SessionUser;
import net.cumba.corej.editor.web.dto.Dtos;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login with a forge token, the session's identity, the CSRF token, logout (§5.1). A session the
 * CSRF endpoint creates before any login lives {@code editor.limits.pre-login-session-timeout}
 * (security review L2: an unauthenticated client cannot pile up 8-hour sessions); logins are
 * limited per client ({@code editor.limits.logins-per-minute}, security review L9: each one costs
 * the forge two or three requests of up to 20 s).
 */
@RestController
@RequestMapping("/api/session")
@Tag(name = "session", description = "Login, identity, CSRF, logout")
public class SessionController
{

    private final LoginService logins;

    private final RateLimiter loginLimit;

    private final int loginsPerMinute;

    private final int preLoginSeconds;

    private final ClientAddress clients;

    public SessionController(LoginService aLogins, EditorProperties aProperties,
            ClientAddress aClients)
    {
        logins = aLogins;
        loginsPerMinute = aProperties.getLimits().getLoginsPerMinute();
        loginLimit = new RateLimiter(loginsPerMinute, Duration.ofMinutes(1));
        preLoginSeconds = (int) Math.clamp(
                aProperties.getLimits().getPreLoginSessionTimeout().toSeconds(), 1L,
                Integer.MAX_VALUE);
        clients = aClients;
    }


    @GetMapping("/csrf")
    @Operation(summary = "The CSRF token to send as X-CSRF-TOKEN on every mutating request, "
            + "the login included; re-fetch after login and after logout")
    public Dtos.CsrfInfo csrf(HttpServletRequest aRequest)
    {
        Object attr = aRequest.getAttribute(CsrfToken.class.getName());
        if (!(attr instanceof CsrfToken token))
        {
            throw Errors.forgeUnavailable("CSRF protection is not active");
        }
        // reading the token stores it, creating the session: short-lived until a login
        Dtos.CsrfInfo info = new Dtos.CsrfInfo(token.getHeaderName(), token.getParameterName(),
                token.getToken());
        HttpSession session = aRequest.getSession(false);
        if (session != null && session.getAttribute(SessionCredentials.ATTRIBUTE) == null)
        {
            session.setMaxInactiveInterval(preLoginSeconds);
        }
        return info;
    }


    @PostMapping("/login")
    @Operation(summary = "Probe the forge with a personal access token and open a session; "
            + "the token decides the mode (edit needs push rights AND a pushable token); "
            + "429 RATE_LIMITED beyond the per-client login limit; rotates the CSRF token")
    public Dtos.SessionInfo login(@RequestBody Dtos.LoginRequest aRequest, HttpServletRequest aHttp,
            HttpServletResponse aResponse)
    {
        if (loginsPerMinute > 0 && !loginLimit.tryAcquire(clients.key(aHttp)))
        {
            throw new EditorException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "logins are limited to " + loginsPerMinute + " per minute per client");
        }
        if (aRequest.login() == null || aRequest.token() == null || aRequest.login().isBlank()
                || aRequest.token().isBlank())
        {
            throw Errors.badRequest("login and token are required");
        }
        SessionUser user = logins.login(
                new ForgeCredentials(aRequest.login().strip(), aRequest.token().strip()), aHttp,
                aResponse);
        return info(user);
    }


    @GetMapping
    @Operation(summary = "The current session's identity and mode (401 without a session)")
    public Dtos.SessionInfo current(@AuthenticationPrincipal @Nullable SessionUser aUser)
    {
        if (aUser == null)
        {
            throw Errors.unauthenticated("no session");
        }
        return info(aUser);
    }


    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Drop the session and its token")
    public void logout(HttpServletRequest aHttp, HttpServletResponse aResponse)
    {
        logins.logout(aHttp, aResponse);
    }


    private static Dtos.SessionInfo info(SessionUser aUser)
    {
        return new Dtos.SessionInfo(aUser.id(), aUser.login(), aUser.name(), aUser.email(),
                aUser.mode(), aUser.defaultBranch());
    }
}
