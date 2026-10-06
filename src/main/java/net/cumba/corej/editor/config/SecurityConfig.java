package net.cumba.corej.editor.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The session-cookie security chain (§5.1.4, review L1):
 * <ul>
 * <li>CSRF on with the session-bound token repository; the SPA fetches the token from
 * {@code GET /api/session/csrf} and sends it as {@code X-CSRF-TOKEN} on every mutating request, the
 * login POST included;</li>
 * <li>no form login, no basic auth, no Spring logout endpoint: {@code POST /api/session/login} and
 * {@code /logout} are the editor's own;</li>
 * <li>no session before it is needed; the login rotates the id;</li>
 * <li>an unauthenticated API call answers 401 as an RFC 9457 problem ({@code UNAUTHENTICATED}),
 * never a redirect; a CSRF failure 403 ({@code CSRF});</li>
 * <li>read endpoints are open to the chain and decide themselves between the session's workspace
 * and the anonymous snapshot (§5.1.6), answering 401 when neither applies; everything else under
 * {@code /api} needs a session;</li>
 * <li>CORS only for the origins in {@code editor.cors.allowed-origins} (§4.2, the
 * sibling-subdomains shape), with credentials and the CSRF header; with the list empty — the
 * default, the same-origin shapes — a cross-origin request is refused and no
 * {@code Access-Control-Allow-Origin} header is ever written.</li>
 * </ul>
 * Cookie flags ({@code HttpOnly; Secure; SameSite=Lax}) and {@code persistent=false} are
 * {@code server.servlet.session.*} in {@code application.yaml}.
 */
@Configuration
public class SecurityConfig
{

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    public SecurityContextRepository securityContextRepository()
    {
        return new HttpSessionSecurityContextRepository();
    }


    /**
     * The CORS policy from {@code editor.cors.allowed-origins}: exact origins only (no patterns),
     * credentials on (the session cookie), the CSRF header allowed and the API's methods. An empty
     * list registers a configuration that matches nothing, so Spring Security's CORS filter refuses
     * every cross-origin request. Not a bean: Spring MVC's {@code HandlerMappingIntrospector} is a
     * {@code CorsConfigurationSource} too, and a second one makes the by-type injection ambiguous.
     */
    static CorsConfigurationSource corsConfigurationSource(EditorProperties aProperties)
    {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.copyOf(aProperties.getCors().getAllowedOrigins()));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }


    @Bean
    public SecurityFilterChain editorSecurity(HttpSecurity aHttp,
            SecurityContextRepository aContexts, EditorProperties aProperties)
        throws Exception
    {
        aHttp.csrf(Customizer.withDefaults())
                .cors(c -> c.configurationSource(corsConfigurationSource(aProperties)))
                .securityContext(sc -> sc.securityContextRepository(aContexts))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation().changeSessionId())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(
                                (req, resp, ex) -> problem(resp, HttpStatus.UNAUTHORIZED,
                                        "UNAUTHENTICATED", "log in first: POST /api/session/login"))
                        .accessDeniedHandler(deniedHandler()))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/api/info", "/api/session/csrf",
                                "/api/explorer", "/api/search", "/api/files", "/api/files/content",
                                "/api/scenarios", "/api/related", "/api/engine/functions",
                                "/api/engine/schema", "/api/engine/ct", "/v3/api-docs",
                                "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        // The engine's read-only POSTs (a buffer in, an analysis / a grid / a
                        // verdict out) are served to the anonymous snapshot too when it is on:
                        // Access.read decides (401 when it is off). CSRF still applies to them.
                        .requestMatchers(HttpMethod.POST, "/api/session/login", "/api/analyse",
                                "/api/cdt/parse", "/api/cdt/splice", "/api/cdt/expectations",
                                "/api/run")
                        .permitAll().requestMatchers("/api/**").authenticated().anyRequest()
                        .permitAll());
        return aHttp.build();
    }


    private static AccessDeniedHandler deniedHandler()
    {
        return (req, resp, ex) ->
        {
            if (ex instanceof CsrfException)
            {
                problem(resp, HttpStatus.FORBIDDEN, "CSRF",
                        "missing or invalid CSRF token: GET /api/session/csrf and send it as "
                                + "X-CSRF-TOKEN");
            }
            else
            {
                problem(resp, HttpStatus.FORBIDDEN, "FORBIDDEN", String.valueOf(ex.getMessage()));
            }
        };
    }


    private static void problem(HttpServletResponse aResponse, HttpStatus aStatus, String aCode,
            String aDetail)
        throws IOException
    {
        aResponse.setStatus(aStatus.value());
        aResponse.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        aResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        var body = JSON.createObjectNode();
        body.put("type", "about:blank");
        body.put("title", aStatus.getReasonPhrase());
        body.put("status", aStatus.value());
        body.put("detail", aDetail);
        body.put("code", aCode);
        aResponse.getWriter().write(JSON.writeValueAsString(body));
    }
}
