package net.cumba.corej.editor.run;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a run worker needs, and nothing else: the two authored texts, the package standards, the
 * scenario text with the file it stands for inside its confined sidecar tree, the dictionary bundle
 * and the store — all by value or by server-chosen absolute path. Written to the worker's stdin as
 * JSON (PLAN-rule-editor §5.8.5).
 *
 * @param checkYaml
 *            the rule buffer
 * @param docYaml
 *            the docs sidecar, or {@code null}
 * @param packageStandards
 *            the package's declared standards as the engine's JSON
 *            ({@code [{"id":"standards/sdtmig/3-4","role":"primary"}, …]})
 * @param cdtText
 *            the scenario buffer
 * @param scenarioFile
 *            the absolute path the scenario is run as (its directory exists inside the sidecar
 *            root)
 * @param sidecarRoot
 *            the absolute sidecar tree the scenario's includes may not leave
 * @param dictionariesDir
 *            the dummy dictionary bundle, or {@code null}
 * @param storePath
 *            the metadata store file, or {@code null}
 * @param engineVersion
 *            the engine version printed in the report, or {@code null}
 */
public record RunJob(String checkYaml, @Nullable String docYaml,
        List<StandardRefJson> packageStandards, String cdtText, String scenarioFile,
        String sidecarRoot, @Nullable String dictionariesDir, @Nullable String storePath,
        @Nullable String engineVersion)
{

    public RunJob
    {
        packageStandards = List.copyOf(packageStandards);
    }

    /** A {@code StandardRef} on the wire ({@code id}, {@code role} as its wire name). */
    public record StandardRefJson(String id, String role)
    {
    }
}
