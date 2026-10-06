package net.cumba.corej.editor.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.cumba.corej.editor.error.Errors;
import net.cumba.corej.editor.forge.ForgeCredentials;
import net.cumba.corej.editor.session.LoginService;
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

/** Login with a forge token, the session's identity, the CSRF token, logout (§5.1). */
@RestController
@RequestMapping("/api/session")
@Tag(name = "session", description = "Login, identity, CSRF, logout")
public class SessionController
{

    private final LoginService logins;

    public SessionController(LoginService aLogins)
    {
        logins = aLogins;
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
        return new Dtos.CsrfInfo(token.getHeaderName(), token.getParameterName(), token.getToken());
    }


    @PostMapping("/login")
    @Operation(summary = "Probe the forge with a personal access token and open a session; "
            + "the token decides the mode (edit needs push rights AND a pushable token)")
    public Dtos.SessionInfo login(@RequestBody Dtos.LoginRequest aRequest, HttpServletRequest aHttp,
            HttpServletResponse aResponse)
    {
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
