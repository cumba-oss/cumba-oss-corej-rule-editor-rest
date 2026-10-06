package net.cumba.corej.editor.web;

import static net.cumba.corej.editor.web.ApiTestSupport.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.cumba.corej.editor.git.AnonymousClone;
import net.cumba.corej.editor.testing.FakeForge;
import net.cumba.corej.editor.testing.RemoteRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * §5.1.6 through the API: with {@code editor.anonymous.enabled=true} and a public repository, the
 * read endpoints serve the snapshot without a session; nothing is ever written through them; and
 * the mode switches itself off when the repository turns private.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiTestSupport.Fakes.class)
class AnonymousApiTest
{

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry aRegistry)
    {
        aRegistry.add("editor.repository.url", () -> ApiTestSupport.remote().url());
        aRegistry.add("editor.workspace-root",
                () -> ApiTestSupport.root().resolve("workspaces-anon").toString());
        aRegistry.add("editor.forge.api-url", () -> "http://127.0.0.1:1/api/v1");
        aRegistry.add("editor.anonymous.enabled", () -> "true");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FakeForge forge;

    @Autowired
    private AnonymousClone anonymous;

    @Test
    void anonymousReadsServeTheSnapshotAndNothingElse() throws Exception
    {
        forge.setAnonymouslyReadable(true);
        anonymous.refresh();
        mvc.perform(get("/api/info")).andExpect(status().isOk())
                .andExpect(jsonPath("$.anonymousRead").value(true));
        mvc.perform(get("/api/explorer")).andExpect(status().isOk())
                .andExpect(jsonPath("$.anonymous").value(true))
                .andExpect(jsonPath("$.branch").value("main"))
                .andExpect(jsonPath("$.families[0].rules[0].id").value(RemoteRepo.RULE_A))
                .andExpect(jsonPath("$.families[0].rules[0].marker").value("CLEAN"));
        mvc.perform(get("/api/files/content").param("path", RemoteRepo.CHECK_A))
                .andExpect(status().isOk()).andExpect(jsonPath("$.path").value(RemoteRepo.CHECK_A));
        mvc.perform(get("/api/files").param("path", "rules-src/docs/CDISC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].name").value(RemoteRepo.RULE_A + ".yaml"));
        mvc.perform(get("/api/search").param("q", "aeterm")).andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].id").value(RemoteRepo.RULE_B));
        mvc.perform(get("/api/files/content").param("path", "rules-src/checks/../pom.xml"))
                .andExpect(status().isBadRequest());

        // never written through, and the session-only surface stays closed
        MvcResult w = mvc.perform(put("/api/files/content").contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"" + RemoteRepo.CHECK_A + "\",\"content\":\"x\","
                        + "\"branch\":\"main\",\"head\":\"x\",\"blob\":null}"))
                .andReturn();
        assertEquals(403, w.getResponse().getStatus(), "no CSRF token: refused before anything");
        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/branches")).andExpect(status().isUnauthorized());

        // the repository turns private: the next refresh switches anonymous read off
        forge.setAnonymouslyReadable(false);
        anonymous.scheduledRefresh();
        mvc.perform(get("/api/info")).andExpect(status().isOk())
                .andExpect(jsonPath("$.anonymousRead").value(false));
        MvcResult closed = mvc.perform(get("/api/explorer")).andExpect(status().isUnauthorized())
                .andReturn();
        assertEquals("UNAUTHENTICATED", body(closed).path("code").asString());
    }
}
