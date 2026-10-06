package net.cumba.corej.editor.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Top-level OpenAPI document metadata (title, version, description) surfaced in the spec + UI. */
@Configuration
public class OpenApiConfig
{

    /**
     * Stable API contract version, independent of the build version (reported by
     * {@code /api/info}). Bump it only on a deliberate, contract-affecting change; the committed
     * {@code openapi.snapshot.json} the SPA generates its types from pins the whole document.
     */
    public static final String API_VERSION = "1.0";

    public static final String TITLE = "corej CDISC Rule Editor API";

    @Bean
    public OpenAPI ruleEditorOpenApi()
    {
        return new OpenAPI().info(new Info().title(TITLE).version(API_VERSION)
                .description("The rule editor's REST API: log in with a forge token, work on "
                        + "a branch of the bound rule repository in a per-user git workspace, "
                        + "read and write rule files, commit, push, and search the corpus. "
                        + "Mutating requests carry the client's {branch, head, blob} and are "
                        + "refused with 409 STALE when the workspace moved on.")
                .license(new License().name("AGPL-3.0-only")
                        .url("https://www.gnu.org/licenses/agpl-3.0.txt")));
    }
}
