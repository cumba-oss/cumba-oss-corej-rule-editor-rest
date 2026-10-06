package net.cumba.corej.editor.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.cumba.corej.editor.corpus.CorpusLayout;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;

/**
 * A bare "forge-side" repository at {@code
 *
<dir>
 * /acme/rules.git} with a miniature split corpus on {@code main}, plus a seed clone through which a
 * test plays the colleague who pushes more commits. Tests reach it over {@code file://}, so no
 * credential is ever needed.
 */
@SuppressWarnings("PMD.CloseResource") // the seed Git is owned by this fixture, closed in close()
public final class RemoteRepo implements AutoCloseable
{

    public static final String OWNER = "acme";

    public static final String NAME = "rules";

    public static final String DEFAULT_BRANCH = "main";

    public static final String RULE_A = "CDISC-AD0001";

    public static final String RULE_B = "CDISC-CG0270";

    public static final String RULE_F = "FDA-SD1142";

    public static final String CHECK_A = CorpusLayout.checkPath("CDISC", RULE_A);

    public static final String DOCS_A = CorpusLayout.docsPath("CDISC", RULE_A);

    public static final String CHECK_B = CorpusLayout.checkPath("CDISC", RULE_B);

    public static final String CHECK_F = CorpusLayout.checkPath("FDA", RULE_F);

    public static final String SCENARIO_A = CorpusLayout.SCENARIOS + "/cdisc/" + RULE_A + "/"
            + RULE_A + "-valid-ADSL.cdt";

    public static final String SCENARIO_B_INVALID = CorpusLayout.SCENARIOS + "/cdisc/" + RULE_B
            + "/" + RULE_B + "-invalid-AE.cdt";

    public static final String SCENARIO_B_VALID = CorpusLayout.SCENARIOS + "/cdisc/" + RULE_B + "/"
            + RULE_B + "-valid-AE.cdt";

    public static final String CHECK_B_TEXT = """
            Core:
              Id: "CDISC-CG0270"
              Status: "Published"
            Description: "AETERM must be present when AEDECOD is populated."
            Scope:
              Domains:
                Include:
                - "AE"
            Check:
              expression: >-
                AEDECOD != "" and AETERM == ""
            Standards:
            - Organization: "CDISC"
              Standards:
              - Name: "SDTMIG"
                Version: "3.4"
                References:
                - Origin: "SDTM and SDTMIG Conformance Rules"
                  Rule_Identifier:
                    Id: "CG0270"
                    Version: "1"
                  Version: "2.0"
            """;

    public static final String SCENARIO_B_INVALID_TEXT = """
            #!RuleTest
            #test CDISC-CG0270 expect=violation domain=AE
            #expectViolationCount 1
            dataset AE label="Adverse Events"
            col STUDYID type=Char label="Study Identifier"
            col USUBJID type=Char label="Unique Subject Identifier"
            col AESEQ type=Num label="Sequence Number"
            col AETERM type=Char label="Reported Term for the Adverse Event"
            col AEDECOD type=Char label="Dictionary-Derived Term"
            ---
            STUDY1 | 01-001 | 1 | "" | HEADACHE
            STUDY1 | 01-001 | 2 | NAUSEA | NAUSEA
            ---
            """;

    public static final Map<String, String> INITIAL_FILES = Map.ofEntries(Map.entry(CHECK_A, """
            Core:
              Id: "CDISC-AD0001"
              Status: "Published"
            Description: "Raise an error when the ADSL dataset is not present."
            Scope:
              Domains:
                Include:
                - "ALL"
            Check:
              expression: >-
                not ds_exists("ADSL")
            Outcome:
              Message: "The ADSL dataset is not present in the submission."
            """), Map.entry(DOCS_A, """
            Core: "CDISC-AD0001"
            Citations:
              CDISC|ADaMIG|1.0||CR1|1:
              - Cited_Guidance: "ADSL and its related metadata are required."
            """), Map.entry(CHECK_B, CHECK_B_TEXT), Map.entry(CHECK_F, """
            Core:
              Id: "FDA-SD1142"
              Status: "Published"
            Description: "Dataset size exceeds the limit."
            Check:
              expression: >-
                dataset_size() > 5
            """), Map.entry(SCENARIO_A, """
            #!RuleTest
            #test CDISC-AD0001 expect=valid domain=ADSL
            dataset ADSL label=Subject-Level Analysis
            col STUDYID type=Char label="Study Identifier"
            ---
            STUDY1
            ---
            """), Map.entry(SCENARIO_B_INVALID, SCENARIO_B_INVALID_TEXT),
            Map.entry(SCENARIO_B_VALID, """
                    #!RuleTest
                    #test CDISC-CG0270 expect=noViolation domain=AE
                    #expectViolationCount 0
                    dataset AE label="Adverse Events"
                    col STUDYID type=Char label="Study Identifier"
                    col USUBJID type=Char label="Unique Subject Identifier"
                    col AETERM type=Char label="Reported Term for the Adverse Event"
                    col AEDECOD type=Char label="Dictionary-Derived Term"
                    ---
                    STUDY1 | 01-001 | HEADACHE | HEADACHE
                    ---
                    """), Map.entry("rules-src/package-standards.json", """
                    {
                      "standards": {
                        "SDTMIG": { "primary": [ "sdtmig/{version}" ] }
                      }
                    }
                    """), Map.entry("rules-src/LANGUAGE-LEVEL", "1\n"),
            Map.entry("rule-guide/reference/functions.md", """
                    # Functions

                    ## Datasets

                    ### ds_exists

                    `ds_exists(name)` → **boolean**

                    True when the named dataset is present in the submission.

                    | parameter | type | required | description |
                    |---|---|---|---|
                    | `name` | string | yes | The dataset to look for |

                    **Example**

                    ```yaml
                    expression: 'not ds_exists("ADSL")'
                    ```
                    """), Map.entry("documentation/rule-check.schema.json", """
                    {
                      "$schema": "https://json-schema.org/draft/2020-12/schema",
                      "type": "object",
                      "properties": {
                        "Core": { "type": "object", "description": "The rule's identity." },
                        "Description": { "type": "string", "description": "What the rule checks." },
                        "Scope": { "type": "object", "description": "Where the rule applies." }
                      }
                    }
                    """), Map.entry("README.md", "# the miniature corpus\n"), Map.entry("pom.xml",
                    "<project><artifactId>outside-the-allow-list</artifactId></project>\n"));

    private final Path bare;

    private final Path seed;

    private final Git seedGit;

    private RemoteRepo(Path aBare, Path aSeed, Git aSeedGit)
    {
        bare = aBare;
        seed = aSeed;
        seedGit = aSeedGit;
    }


    /** Creates the bare repository and its initial commit under {@code aDir}. */
    public static RemoteRepo create(Path aDir) throws IOException, GitAPIException
    {
        Path bare = aDir.resolve(OWNER).resolve(NAME + ".git");
        Files.createDirectories(bare);
        try (Git b = Git.init().setBare(true).setInitialBranch(DEFAULT_BRANCH)
                .setDirectory(bare.toFile()).call())
        {
            b.getRepository().getConfig().save();
        }
        Path seed = aDir.resolve("seed");
        Files.createDirectories(seed);
        Git seedGit = Git.init().setInitialBranch(DEFAULT_BRANCH).setDirectory(seed.toFile())
                .call();
        StoredConfig c = seedGit.getRepository().getConfig();
        c.setString("remote", "origin", "url", bare.toUri().toString());
        c.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
        c.setString("user", null, "name", "Seed Colleague");
        c.setString("user", null, "email", "seed@example.test");
        c.save();
        RemoteRepo r = new RemoteRepo(bare, seed, seedGit);
        r.commitAndPush(DEFAULT_BRANCH, INITIAL_FILES, "initial corpus");
        return r;
    }


    /** The {@code file://} clone URL. */
    public String url()
    {
        return bare.toUri().toString();
    }


    public Path bareDir()
    {
        return bare;
    }


    /** The seed clone's git, for tests that need more than {@link #commitAndPush}. */
    public Git seed()
    {
        return seedGit;
    }


    public Path seedDir()
    {
        return seed;
    }


    /**
     * As a colleague: switch the seed clone to the branch (created from the current HEAD when new),
     * write the files, commit, push. Returns the commit.
     */
    public RevCommit commitAndPush(String aBranch, Map<String, String> aFiles, String aMessage)
        throws IOException, GitAPIException
    {
        Repository repo = seedGit.getRepository();
        boolean unborn = repo.resolve(Constants.HEAD) == null;
        if (!unborn && !aBranch.equals(repo.getBranch()))
        {
            seedGit.fetch().setRemote("origin").setRemoveDeletedRefs(true).call();
            if (repo.exactRef(Constants.R_HEADS + aBranch) == null)
            {
                boolean remote = repo.exactRef(Constants.R_REMOTES + "origin/" + aBranch) != null;
                seedGit.branchCreate().setName(aBranch)
                        .setStartPoint(remote ? "origin/" + aBranch : Constants.HEAD).call();
            }
            seedGit.checkout().setName(aBranch).call();
        }
        else if (!unborn)
        {
            // the colleague works on top of what the remote has: when someone else moved the
            // branch, pull it (a hard reset, since the seed holds nothing of its own then).
            // Never when nothing moved: a reset also wipes what a test staged on purpose.
            seedGit.fetch().setRemote("origin").setRemoveDeletedRefs(true).call();
            ObjectId remoteTip = repo.resolve(Constants.R_REMOTES + "origin/" + aBranch);
            if (remoteTip != null && !remoteTip.equals(repo.resolve(Constants.HEAD)))
            {
                seedGit.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/" + aBranch)
                        .call();
            }
        }
        for (Map.Entry<String, String> f : aFiles.entrySet())
        {
            Path p = seed.resolve(f.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getValue(), StandardCharsets.UTF_8);
            seedGit.add().addFilepattern(f.getKey()).call();
        }
        RevCommit commit = seedGit.commit().setMessage(aMessage)
                .setAuthor("Seed Colleague", "seed@example.test")
                .setCommitter("Seed Colleague", "seed@example.test").call();
        String ref = Constants.R_HEADS + aBranch;
        for (PushResult r : seedGit.push().setRemote("origin")
                .setRefSpecs(new RefSpec(ref + ":" + ref)).call())
        {
            RemoteRefUpdate u = r.getRemoteUpdate(ref);
            if (u == null || (u.getStatus() != RemoteRefUpdate.Status.OK
                    && u.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE))
            {
                throw new IOException("the fixture's push of " + aBranch + " was rejected: "
                        + (u == null ? "no result" : u.getStatus() + " " + u.getMessage()));
            }
        }
        return commit;
    }


    /** Deletes a branch on the bare repository (the forge's "delete branch after merge"). */
    public void deleteRemoteBranch(String aBranch) throws IOException
    {
        try (Repository r = Git.open(bare.toFile()).getRepository())
        {
            RefUpdate u = r.updateRef(Constants.R_HEADS + aBranch);
            u.setForceUpdate(true);
            u.delete();
        }
    }


    /** The tip of a branch on the bare repository. */
    public ObjectId tip(String aBranch) throws IOException
    {
        try (Repository r = Git.open(bare.toFile()).getRepository())
        {
            ObjectId id = r.resolve(Constants.R_HEADS + aBranch);
            if (id == null)
            {
                throw new IOException("no branch " + aBranch + " on the remote");
            }
            return id;
        }
    }


    @Override
    public void close()
    {
        seedGit.close();
    }
}
