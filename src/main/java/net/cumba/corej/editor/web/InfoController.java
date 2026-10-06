package net.cumba.corej.editor.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Locale;
import net.cumba.corej.editor.config.BuildInfo;
import net.cumba.corej.editor.config.EditorProperties;
import net.cumba.corej.editor.config.RepositoryCoordinates;
import net.cumba.corej.editor.engine.EngineStore;
import net.cumba.corej.editor.git.AnonymousClone;
import net.cumba.corej.editor.run.RunService;
import net.cumba.corej.editor.web.dto.Dtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The instance's identity: what it is, which repository it serves, whether anonymous read is on.
 */
@RestController
@RequestMapping("/api/info")
@Tag(name = "info", description = "Service metadata")
public class InfoController
{

    /** ⛔ do-not-rename: {@code spring.application.name}, asserted by the web module too. */
    static final String SERVICE = "corej-rule-editor-rest";

    private final RepositoryCoordinates repository;

    private final String forge;

    private final AnonymousClone anonymous;

    private final EngineStore store;

    private final RunService runs;

    private final long runTimeoutSeconds;

    private final int maxBufferBytes;

    public InfoController(EditorProperties aProperties, AnonymousClone aAnonymous,
            EngineStore aStore, RunService aRuns)
    {
        repository = aProperties.coordinates();
        forge = aProperties.getForge().getType().name().toLowerCase(Locale.ROOT);
        anonymous = aAnonymous;
        store = aStore;
        runs = aRuns;
        runTimeoutSeconds = aProperties.getRun().getTimeout().toSeconds();
        maxBufferBytes = aProperties.getLimits().getMaxBufferBytes();
    }


    @GetMapping
    @Operation(summary = "Service name, build version and the bound repository")
    public Dtos.ServiceInfo info()
    {
        BuildInfo b = BuildInfo.current();
        return new Dtos.ServiceInfo(SERVICE, b.version(), b.commit(),
                new Dtos.RepositoryInfo(
                        repository.url(), repository.owner(), repository.name(), forge),
                anonymous.available(),
                new Dtos.EngineInfo(runs.engineVersionOrNull(), EngineStore.languageLevel(),
                        store.available(), store.formatVersion(), store.unavailableReason(),
                        runTimeoutSeconds, maxBufferBytes));
    }
}
