package net.cumba.corej.editor;

import net.cumba.corej.editor.config.EditorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point of the rule editor's REST service (databrowser-meta
 * {@code plans/PLAN-rule-editor.md}): forge login, one git workspace per forge user, the branch /
 * file / commit / push operations under the editor's server-side rules (§5.8), the explorer and the
 * free-text search over the rule corpus. Engine-free in phase 1; the authoring API arrives in phase
 * 3.
 *
 * <p>
 * Scheduling drives the two background sweeps: workspace retention (§5.1.5) and the optional
 * anonymous clone's refresh (§5.1.6).
 * </p>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(EditorProperties.class)
public class RuleEditorApplication
{

    public static void main(String[] args)
    {
        SpringApplication.run(RuleEditorApplication.class, args);
    }
}
