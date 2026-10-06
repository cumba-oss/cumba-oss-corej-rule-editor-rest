package net.cumba.corej.editor.forge;

import net.cumba.corej.editor.config.EditorProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one {@link ForgeClient} of this instance, chosen by {@code editor.forge.type}. A test context
 * adds a {@code @Primary} fake beside it: no test talks to a real forge.
 */
@Configuration
public class ForgeConfig
{

    @Bean
    public ForgeClient forgeClient(EditorProperties aProperties)
    {
        // checked like the repository URL: https, no credentials in it (security review L3)
        String api = aProperties.forgeApiUrl();
        return switch (aProperties.getForge().getType())
        {
        case GITEA -> new GiteaClient(api);
        case GITHUB -> new GitHubClient(api);
        };
    }
}
