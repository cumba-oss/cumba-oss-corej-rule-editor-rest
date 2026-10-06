package net.cumba.corej.editor.web;

import static net.cumba.corej.editor.web.ApiTestSupport.CSRF_HEADER;
import static net.cumba.corej.editor.web.ApiTestSupport.body;
import static net.cumba.corej.editor.web.ApiTestSupport.csrf;
import static net.cumba.corej.editor.web.ApiTestSupport.json;
import static net.cumba.corej.editor.web.ApiTestSupport.login;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import net.cumba.corej.editor.HomeStaysCleanExtension;
import net.cumba.corej.editor.testing.FakeForge;
import net.cumba.corej.editor.testing.RemoteRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The API end to end over MockMvc with the security chain: the login flow and the modes (§5.1.3),
 * every §5.8 refusal as the SPA sees it, and the save → commit → push round trip (§5.2). The remote
 * is a local bare repository; the forge is {@link FakeForge}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiTestSupport.Fakes.class)
@ExtendWith(HomeStaysCleanExtension.class)
class ApiTest
{

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry aRegistry)
    {
        aRegistry.add("editor.repository.url", () -> ApiTestSupport.remote().url());
        aRegistry.add("editor.workspace-root",
                () -> ApiTestSupport.root().resolve("workspaces-api").toString());
        aRegistry.add("editor.forge.api-url", () -> "http://127.0.0.1:1/api/v1");
        aRegistry.add("editor.anonymous.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FakeForge forge;

    private static String problem(MvcResult aResult) throws Exception
    {
        String type = String.valueOf(aResult.getResponse().getContentType());
        assertTrue(type.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE), type);
        return body(aResult).path("code").asString();
    }


    @Test
    void loginNeedsTheCsrfTokenAndRefusesABadToken() throws Exception
    {
        MockHttpSession session = new MockHttpSession();
        MvcResult noCsrf = mvc
                .perform(post("/api/session/login").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"mw\",\"token\":\"write-token\"}"))
                .andExpect(status().isForbidden()).andReturn();
        assertEquals("CSRF", problem(noCsrf));

        String csrf = csrf(mvc, session);
        MvcResult bad = mvc
                .perform(json(post("/api/session/login"), session, csrf,
                        "{\"login\":\"mw\",\"token\":\"nope\"}"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertEquals("FORGE_REFUSED", problem(bad));
        MvcResult blank = mvc
                .perform(json(post("/api/session/login"), session, csrf,
                        "{\"login\":\"\",\"token\":\"x\"}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertEquals("BAD_REQUEST", problem(blank));
        MvcResult none = mvc.perform(get("/api/session").session(session))
                .andExpect(status().isUnauthorized()).andReturn();
        assertEquals("UNAUTHENTICATED", problem(none));
    }


    @Test
    void unauthenticatedCallsAre401ProblemsNotRedirects() throws Exception
    {
        assertEquals("UNAUTHENTICATED", problem(
                mvc.perform(get("/api/status")).andExpect(status().isUnauthorized()).andReturn()));
        assertEquals("UNAUTHENTICATED", problem(mvc.perform(get("/api/explorer"))
                .andExpect(status().isUnauthorized()).andReturn()));
        assertEquals("UNAUTHENTICATED", problem(mvc.perform(get("/api/search?q=x"))
                .andExpect(status().isUnauthorized()).andReturn()));
        mvc.perform(get("/api/info")).andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("corej-rule-editor-rest"))
                .andExpect(jsonPath("$.repository.owner").value(RemoteRepo.OWNER))
                .andExpect(jsonPath("$.repository.name").value(RemoteRepo.NAME))
                .andExpect(jsonPath("$.repository.forge").value("gitea"))
                .andExpect(jsonPath("$.anonymousRead").value(false))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }


    @Test
    void theTokenDecidesTheMode() throws Exception
    {
        MockHttpSession writer = login(mvc, "mw", FakeForge.WRITE_TOKEN);
        mvc.perform(get("/api/session").session(writer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(8)).andExpect(jsonPath("$.mode").value("EDIT"))
                .andExpect(jsonPath("$.name").value("Michael"))
                .andExpect(jsonPath("$.defaultBranch").value("main"));

        MockHttpSession reader = login(mvc, "rv", FakeForge.READ_TOKEN);
        mvc.perform(get("/api/session").session(reader)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("READ_ONLY"))
                .andExpect(jsonPath("$.name").value("rv"));
        mvc.perform(get("/api/status").session(reader)).andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true));

        // review M4: the ROLE may push, the TOKEN may not
        int probes = forge.receivePackProbes();
        MockHttpSession roleOnly = login(mvc, "mw", FakeForge.ROLE_ONLY_TOKEN);
        mvc.perform(get("/api/session").session(roleOnly)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("READ_ONLY"));
        assertEquals(probes + 1, forge.receivePackProbes(), "the receive-pack probe ran");

        mvc.perform(
                post("/api/session/logout").session(writer).header(CSRF_HEADER, csrf(mvc, writer)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/session").session(writer)).andExpect(status().isUnauthorized());
    }


    @Test
    void readOnlySessionsGet403OnEveryMutatingEndpoint() throws Exception
    {
        MockHttpSession reader = login(mvc, "rv", FakeForge.READ_TOKEN);
        String csrf = csrf(mvc, reader);
        JsonNode st = body(mvc.perform(get("/api/status").session(reader)).andReturn());
        String expect = "\"branch\":\"" + st.path("branch").asString() + "\",\"head\":\""
                + st.path("head").asString() + "\"";
        assertEquals("READ_ONLY",
                problem(mvc
                        .perform(json(put("/api/files/content"), reader, csrf,
                                "{\"path\":\"" + RemoteRepo.CHECK_A + "\",\"content\":\"x\","
                                        + expect + ",\"blob\":null}"))
                        .andExpect(status().isForbidden()).andReturn()));
        assertEquals("READ_ONLY",
                problem(mvc
                        .perform(json(post("/api/commit"), reader, csrf,
                                "{\"message\":\"m\"," + expect + "}"))
                        .andExpect(status().isForbidden()).andReturn()));
        assertEquals("READ_ONLY",
                problem(mvc.perform(json(post("/api/push"), reader, csrf, "{" + expect + "}"))
                        .andExpect(status().isForbidden()).andReturn()));
        assertEquals("READ_ONLY",
                problem(mvc
                        .perform(json(post("/api/branches"), reader, csrf,
                                "{\"name\":\"rv-branch\"," + expect + "}"))
                        .andExpect(status().isForbidden()).andReturn()));
        assertEquals("READ_ONLY",
                problem(mvc
                        .perform(json(post("/api/branches/update-from-default"), reader, csrf,
                                "{" + expect + "}"))
                        .andExpect(status().isForbidden()).andReturn()));
        assertEquals("READ_ONLY", problem(mvc
                .perform(json(post("/api/discard"), reader, csrf, "{\"paths\":[]," + expect + "}"))
                .andExpect(status().isForbidden()).andReturn()));
        // reading, switching and running without saving stay open to a read-only user (O12)
        mvc.perform(get("/api/explorer").session(reader)).andExpect(status().isOk())
                .andExpect(jsonPath("$.families[0].name").value("CDISC"))
                .andExpect(jsonPath("$.families[0].rules[0].marker").value("CLEAN"))
                .andExpect(jsonPath("$.anonymous").value(false));
        mvc.perform(get("/api/files/content").param("path", RemoteRepo.CHECK_A).session(reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.blob").isNotEmpty());
        mvc.perform(get("/api/search").param("q", "adsl").session(reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.hits[0].id").value(RemoteRepo.RULE_A));
        mvc.perform(get("/api/branches").param("fetch", "true").session(reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.current").value("main"));
    }


    @Test
    void pathsOutsideTheAllowListAre400() throws Exception
    {
        MockHttpSession s = login(mvc, "mw", FakeForge.WRITE_TOKEN);
        String csrf = csrf(mvc, s);
        for (String bad : new String[]
        {
                "rules-src/checks/../../pom.xml", ".git/config", "rules-src/checks/.git/x",
                "/etc/passwd", "src/main/java/X.java", "rules-src/checks/CDISC/.hidden.yaml"
        })
        {
            MvcResult r = mvc.perform(get("/api/files/content").param("path", bad).session(s))
                    .andExpect(status().isBadRequest()).andReturn();
            assertEquals("BAD_PATH", problem(r), bad);
            assertEquals(bad, body(r).path("path").asString());
        }
        JsonNode st = body(mvc.perform(get("/api/status").session(s)).andReturn());
        MvcResult w = mvc.perform(json(put("/api/files/content"), s, csrf,
                "{\"path\":\".git/hooks/pre-commit\",\"content\":\"#!/bin/sh\\n\",\"branch\":\""
                        + st.path("branch").asString() + "\",\"head\":\""
                        + st.path("head").asString() + "\",\"blob\":null}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertEquals("BAD_PATH", problem(w));
        mvc.perform(get("/api/files/content").param("path", "rules-src/checks/CDISC/nope.yaml")
                .session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/api/files").param("path", "rules-src/checks").session(s))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].name").value("CDISC"))
                .andExpect(jsonPath("$.entries[0].directory").value(true));
    }


    @Test
    void saveCommitPushRoundTripWithStaleAndDirtyRefusals() throws Exception
    {
        MockHttpSession s = login(mvc, "mw", FakeForge.WRITE_TOKEN);
        String csrf = csrf(mvc, s);
        JsonNode st = body(mvc.perform(get("/api/status").session(s)).andReturn());
        String branch = st.path("branch").asString();
        String head = st.path("head").asString();
        assertEquals("main", branch);

        // writes on the default branch are refused
        JsonNode file = body(
                mvc.perform(get("/api/files/content").param("path", RemoteRepo.CHECK_A).session(s))
                        .andReturn());
        String write = "{\"path\":\"" + RemoteRepo.CHECK_A
                + "\",\"content\":\"Core: edited\\n\",\"branch\":\"%s\",\"head\":\"%s\",\"blob\":\"%s\"}";
        assertEquals("DEFAULT_BRANCH",
                problem(mvc
                        .perform(json(put("/api/files/content"), s, csrf,
                                write.formatted(branch, head, file.path("blob").asString())))
                        .andExpect(status().isConflict()).andReturn()));

        // a stale head is refused with the current one in the problem
        MvcResult stale = mvc
                .perform(json(post("/api/branches"), s, csrf,
                        "{\"name\":\"api-feature\",\"branch\":\"main\",\"head\":\""
                                + "0000000000000000000000000000000000000000\"}"))
                .andExpect(status().isConflict()).andReturn();
        assertEquals("STALE", problem(stale));
        assertEquals(head, body(stale).path("head").asString());

        JsonNode branches = body(mvc
                .perform(json(post("/api/branches"), s, csrf,
                        "{\"name\":\"api-feature\",\"branch\":\"main\",\"head\":\"" + head + "\"}"))
                .andExpect(status().isOk()).andReturn());
        assertEquals("api-feature", branches.path("current").asString());
        head = branches.path("head").asString();

        // save
        JsonNode written = body(mvc
                .perform(json(put("/api/files/content"), s, csrf,
                        write.formatted("api-feature", head, file.path("blob").asString())))
                .andExpect(status().isOk()).andReturn());
        assertNotEquals(file.path("blob").asString(), written.path("blob").asString());
        // the same blob again: stale
        assertEquals("STALE",
                problem(mvc
                        .perform(json(put("/api/files/content"), s, csrf,
                                write.formatted("api-feature", head, file.path("blob").asString())))
                        .andExpect(status().isConflict()).andReturn()));
        mvc.perform(get("/api/status").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.uncommitted[0].path").value(RemoteRepo.CHECK_A))
                .andExpect(jsonPath("$.uncommitted[0].kind").value("MODIFIED"))
                .andExpect(jsonPath("$.unpushed.unpushed").value(true))
                .andExpect(jsonPath("$.unpushed.hasTrackingRef").value(false));
        mvc.perform(get("/api/explorer").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.families[0].rules[0].marker").value("MODIFIED"));

        // switching with a dirty tree is refused and names the file
        MvcResult dirty = mvc
                .perform(json(post("/api/branches/switch"), s, csrf,
                        "{\"name\":\"main\",\"branch\":\"api-feature\",\"head\":\"" + head + "\"}"))
                .andExpect(status().isConflict()).andReturn();
        assertEquals("DIRTY_TREE", problem(dirty));
        assertEquals(RemoteRepo.CHECK_A, body(dirty).path("paths").get(0).asString());

        // commit, hooks off, with the user's identity
        JsonNode committed = body(mvc.perform(json(post("/api/commit"), s, csrf,
                "{\"message\":\"edit A via API\",\"branch\":\"api-feature\",\"head\":\"" + head
                        + "\"}"))
                .andExpect(status().isOk()).andReturn());
        assertEquals("edit A via API", committed.path("commit").path("message").asString());
        assertEquals("Michael", committed.path("commit").path("author").asString());
        head = committed.path("head").asString();
        assertEquals("NOTHING_TO_COMMIT",
                problem(mvc
                        .perform(json(post("/api/commit"), s, csrf,
                                "{\"message\":\"again\",\"branch\":\"api-feature\",\"head\":\""
                                        + head + "\"}"))
                        .andExpect(status().isConflict()).andReturn()));

        // push
        JsonNode pushed = body(mvc
                .perform(json(post("/api/push"), s, csrf,
                        "{\"branch\":\"api-feature\",\"head\":\"" + head + "\"}"))
                .andExpect(status().isOk()).andReturn());
        assertEquals("fake://pr/main...api-feature", pushed.path("pullRequestUrl").asString());
        assertTrue(pushed.path("push").path("created").asBoolean());
        assertEquals(head, ApiTestSupport.remote().tip("api-feature").name());
        mvc.perform(get("/api/status").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.unpushed.unpushed").value(false))
                .andExpect(jsonPath("$.uncommitted").isEmpty());

        // a colleague moves main; update-from-default fast-forwards the feature branch
        ApiTestSupport.remote().commitAndPush("main", Map.of("README.md", "# moved\n"), "move");
        JsonNode updated = body(mvc
                .perform(json(post("/api/branches/update-from-default"), s, csrf,
                        "{\"branch\":\"api-feature\",\"head\":\"" + head + "\"}"))
                .andExpect(status().isOk()).andReturn());
        assertEquals("MERGED", updated.path("merge").path("status").asString());
        head = updated.path("head").asString();

        // and update-from-remote of the own branch: already up to date after the push
        JsonNode same = body(mvc
                .perform(json(post("/api/branches/update-from-remote"), s, csrf,
                        "{\"branch\":\"api-feature\",\"head\":\"" + head + "\"}"))
                .andExpect(status().isOk()).andReturn());
        assertEquals("ALREADY_UP_TO_DATE", same.path("merge").path("status").asString());

        // back to main, delete the (pushed) branch: refused while the remote still has it? No:
        // nothing is unpushed, so it may go.
        mvc.perform(json(post("/api/branches/switch"), s, csrf,
                "{\"name\":\"main\",\"branch\":\"api-feature\",\"head\":\"" + head + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.current").value("main"));
        assertEquals("UNPUSHED", problem(mvc
                .perform(json(post("/api/branches/delete"), s, csrf, "{\"name\":\"api-feature\"}"))
                .andExpect(status().isConflict()).andReturn()),
                "the merge commit from update-from-default was never pushed");
        mvc.perform(json(post("/api/branches/delete"), s, csrf, "{\"name\":\"nope\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/branches/delete"), s, csrf, "{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }


    @Test
    void discardRestoresTheSavedChangesAfterADirtyTreeRefusal() throws Exception
    {
        MockHttpSession s = login(mvc, "mw", FakeForge.WRITE_TOKEN);
        String csrf = csrf(mvc, s);
        JsonNode st = body(mvc.perform(get("/api/status").session(s)).andReturn());
        JsonNode created = body(mvc
                .perform(json(post("/api/branches"), s, csrf,
                        "{\"name\":\"api-discard\",\"branch\":\"" + st.path("branch").asString()
                                + "\",\"head\":\"" + st.path("head").asString() + "\"}"))
                .andExpect(status().isOk()).andReturn());
        String head = created.path("head").asString();
        String expect = "\"branch\":\"api-discard\",\"head\":\"" + head + "\"";
        JsonNode original = body(
                mvc.perform(get("/api/files/content").param("path", RemoteRepo.CHECK_B).session(s))
                        .andReturn());
        String newRule = "rules-src/checks/DRAFT/DRAFT-D0001.yaml";
        mvc.perform(json(put("/api/files/content"), s, csrf,
                "{\"path\":\"" + RemoteRepo.CHECK_B + "\",\"content\":\"Core: dirty\\n\"," + expect
                        + ",\"blob\":\"" + original.path("blob").asString() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(json(put("/api/files/content"), s, csrf, "{\"path\":\"" + newRule
                + "\",\"content\":\"Core: new\\n\"," + expect + ",\"blob\":null}"))
                .andExpect(status().isOk());
        assertEquals("DIRTY_TREE",
                problem(mvc
                        .perform(json(post("/api/branches/switch"), s, csrf,
                                "{\"name\":\"main\"," + expect + "}"))
                        .andExpect(status().isConflict()).andReturn()));

        String both = "\"paths\":[\"" + RemoteRepo.CHECK_B + "\",\"" + newRule + "\"]";
        // without the CSRF token: refused like every mutating request
        assertEquals("CSRF", problem(mvc
                .perform(post("/api/discard").session(s).contentType(MediaType.APPLICATION_JSON)
                        .content("{" + both + "," + expect + "}"))
                .andExpect(status().isForbidden()).andReturn()));
        // the confirmed list must be the current one: STALE names the current files
        MvcResult partial = mvc
                .perform(json(post("/api/discard"), s, csrf,
                        "{\"paths\":[\"" + RemoteRepo.CHECK_B + "\"]," + expect + "}"))
                .andExpect(status().isConflict()).andReturn();
        assertEquals("STALE", problem(partial));
        assertEquals(2, body(partial).path("paths").size());
        assertEquals("BAD_REQUEST",
                problem(mvc.perform(json(post("/api/discard"), s, csrf, "{" + expect + "}"))
                        .andExpect(status().isBadRequest()).andReturn()),
                "the paths are required");
        assertEquals("STALE",
                problem(mvc
                        .perform(json(post("/api/discard"), s, csrf,
                                "{" + both + ",\"branch\":\"api-discard\",\"head\":\""
                                        + "0000000000000000000000000000000000000000\"}"))
                        .andExpect(status().isConflict()).andReturn()));
        mvc.perform(get("/api/files/content").param("path", RemoteRepo.CHECK_B).session(s))
                .andExpect(jsonPath("$.content").value("Core: dirty\n"));

        JsonNode discarded = body(
                mvc.perform(json(post("/api/discard"), s, csrf, "{" + both + "," + expect + "}"))
                        .andExpect(status().isOk()).andReturn());
        assertEquals(2, discarded.path("discarded").size());
        assertEquals("api-discard", discarded.path("branch").asString());
        assertEquals(head, discarded.path("head").asString(), "HEAD does not move");
        mvc.perform(get("/api/status").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.uncommitted").isEmpty());
        mvc.perform(get("/api/files/content").param("path", RemoteRepo.CHECK_B).session(s))
                .andExpect(jsonPath("$.blob").value(original.path("blob").asString()));
        mvc.perform(get("/api/files/content").param("path", newRule).session(s))
                .andExpect(status().isNotFound());
        mvc.perform(
                json(post("/api/branches/switch"), s, csrf, "{\"name\":\"main\"," + expect + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.current").value("main"));
    }


    @Test
    void malformedBodiesAreProblemsToo() throws Exception
    {
        MockHttpSession s = login(mvc, "mw", FakeForge.WRITE_TOKEN);
        String csrf = csrf(mvc, s);
        mvc.perform(json(post("/api/commit"), s, csrf, "{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(json(post("/api/commit"), s, csrf,
                "{\"message\":\"\",\"branch\":\"main\"," + "\"head\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/no-such-endpoint").session(s)).andExpect(status().isNotFound());
    }
}
