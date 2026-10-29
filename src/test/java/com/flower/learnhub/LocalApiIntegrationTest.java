package com.flower.learnhub;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:local-api-tests;DB_CLOSE_DELAY=-1",
        "app.access-token=" + AppSettingsTest.TOKEN,
        "app.embedding-api-base-url=", "app.embedding-api-key=", "app.embedding-model=",
        "app.chat-api-base-url=", "app.chat-api-key=", "app.chat-model="
})
@AutoConfigureMockMvc
class LocalApiIntegrationTest {
    private static final String AUTH = "Bearer " + AppSettingsTest.TOKEN;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() { jdbc.update("DELETE FROM knowledge_bases"); }

    private ResultActions request(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", AUTH));
    }
    private JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    private JsonNode postJson(String path, Object body, int status) throws Exception {
        return json(request(post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().is(status)));
    }
    private long kb() throws Exception { return postJson("/api/kbs", Map.of("name", "我的学习资料"), 201).path("id").asLong(); }
    private JsonNode upload(long kbId, String name, byte[] bytes, int expected) throws Exception {
        return json(request(multipart("/api/kbs/{id}/documents", kbId)
                .file(new MockMultipartFile("file", name, "application/octet-stream", bytes))).andExpect(status().is(expected)));
    }
    private long session(long kbId) throws Exception {
        return postJson("/api/sessions", Map.of("kbId", kbId, "title", "学习会话"), 201).path("id").asLong();
    }

    @Test void allApiRoutesRequireBearerAndDoNotExposeCredentials() throws Exception {
        mvc.perform(get("/api/config")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().string("WWW-Authenticate", "Bearer")).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/kbs").contentType(MediaType.APPLICATION_JSON).content("invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/config").header("Authorization", "Bearer incorrect"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("incorrect"))));
        mvc.perform(options("/api/kbs").header("Origin", "https://untrusted.example"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        request(get("/api/config")).andExpect(status().isOk()).andExpect(jsonPath("$.retrievalMode").value("local_hash"))
                .andExpect(jsonPath("$.chatEnabled").value(false)).andExpect(jsonPath("$.embeddingModel").value(""))
                .andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.apiKey").doesNotExist());
        mvc.perform(get("/nonexistent-static-test-file.css")).andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api;matrix=1/config", "/api//config", "/else/../api/config", "/%61pi/config", "/api%2Fconfig", "/api/./config"})
    void normalizedApiPathsCannotBypassAuthentication(String path) throws Exception {
        mvc.perform(get(java.net.URI.create(path))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test void documentRetrievalAndHistoryPersistWithoutInventingAnAnswer() throws Exception {
        long kbId = kb();
        JsonNode document = upload(kbId, "学习.md", "知识检索帮助知识学习。Spring Boot 提供应用开发能力。".getBytes(StandardCharsets.UTF_8), 201);
        assertThat(document.path("id").isIntegralNumber()).isTrue();
        assertThat(document.path("chunkCount").asInt()).isEqualTo(1);
        assertThat(document.path("createdAt").asText()).isNotBlank();
        request(get("/api/kbs")).andExpect(jsonPath("$[0].documentCount").value(1)).andExpect(jsonPath("$[0].chunkCount").value(1));
        request(get("/api/kbs/{id}/documents", kbId)).andExpect(jsonPath("$[0].name").value("学习.md"));
        JsonNode search = postJson("/api/kbs/" + kbId + "/search", Map.of("query", "知识检索"), 200);
        assertThat(search.path("retrievalMode").asText()).isEqualTo("local_hash");
        assertThat(search.path("hits")).hasSize(1);
        assertThat(search.at("/hits/0/page").isNull()).isTrue();
        assertThat(search.at("/hits/0/ordinal").asInt()).isEqualTo(1);
        assertThat(search.at("/hits/0/sourceAvailable").asBoolean()).isTrue();
        assertThat(search.at("/hits/0/score").asDouble()).isBetween(0.1, 1.0);
        long sessionId = session(kbId);
        JsonNode answer = postJson("/api/sessions/" + sessionId + "/messages", Map.of("question", "知识检索"), 200);
        assertThat(answer.path("content").isNull()).isTrue();
        assertThat(answer.path("mode").asText()).isEqualTo("retrieval_only");
        assertThat(answer.path("role").asText()).isEqualTo("assistant");
        assertThat(answer.path("citations")).hasSize(1);
        // A separate repository instance reads committed H2 rows, rather than an in-memory service cache.
        LearnHubStore reopened = new LearnHubStore(jdbc, mapper);
        var persisted = reopened.messages(sessionId, false);
        assertThat(persisted).hasSize(2);
        assertThat(persisted).extracting(ApiModels.Message::role).containsExactly("user", "assistant");
        assertThat(persisted.getFirst().mode()).isEqualTo("user");
        assertThat(persisted.getLast().citations().getFirst().text()).contains("知识检索");
        assertThat(jdbc.queryForObject("SELECT extracted_text FROM documents WHERE id=?", String.class, document.path("id").asLong()))
                .contains("知识检索");
        assertThat(jdbc.queryForObject("SELECT citations_json FROM messages WHERE id=?", String.class, answer.path("id").asLong()))
                .doesNotContain("Spring Boot");
    }

    @Test void emptyLibraryAndUnrelatedQueriesReturnNoEvidence() throws Exception {
        long kbId = kb();
        JsonNode empty = postJson("/api/kbs/" + kbId + "/search", Map.of("query", "知识检索"), 200);
        assertThat(empty.path("hits")).isEmpty();
        upload(kbId, "words.txt", "apple banana pear".getBytes(StandardCharsets.UTF_8), 201);
        JsonNode unrelated = postJson("/api/kbs/" + kbId + "/search", Map.of("query", "海洋物理"), 200);
        assertThat(unrelated.path("hits")).isEmpty();
        JsonNode answer = postJson("/api/sessions/" + session(kbId) + "/messages", Map.of("question", "海洋物理"), 200);
        assertThat(answer.path("content").isNull()).isTrue();
        assertThat(answer.path("citations")).isEmpty();
    }

    @Test void pdfImportPreservesPagesAndDoesNotCrossPageBoundaries() throws Exception {
        long kbId = kb();
        JsonNode document = upload(kbId, "book.pdf", TestDocuments.pdf(2, true, false), 201);
        assertThat(document.path("chunkCount").asInt()).isEqualTo(2);
        JsonNode search = postJson("/api/kbs/" + kbId + "/search", Map.of("query", "knowledge", "topK", 10), 200);
        assertThat(search.path("hits")).hasSize(2);
        assertThat(search.at("/hits/0/page").asInt()).isEqualTo(1);
        assertThat(search.at("/hits/1/page").asInt()).isEqualTo(2);
        assertThat(search.at("/hits/0/text").asText()).contains("page 1").doesNotContain("page 2");
    }

    @Test void deletingDocumentsRedactsHistoricalCitationsAndClearsVectors() throws Exception {
        long kbId = kb();
        long documentId = upload(kbId, "facts.txt", "知识检索用于学习".getBytes(StandardCharsets.UTF_8), 201).path("id").asLong();
        long sessionId = session(kbId);
        postJson("/api/sessions/" + sessionId + "/messages", Map.of("question", "知识检索"), 200);
        request(delete("/api/documents/{id}", documentId)).andExpect(status().isNoContent());
        request(get("/api/sessions/{id}/messages", sessionId)).andExpect(jsonPath("$[1].citations[0].sourceAvailable").value(false))
                .andExpect(jsonPath("$[1].citations[0].text").value(""));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chunks", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT vector_mode FROM knowledge_bases WHERE id=?", String.class, kbId)).isNull();
        assertThat(postJson("/api/kbs/" + kbId + "/search", Map.of("query", "知识检索"), 200).path("hits")).isEmpty();
        request(delete("/api/documents/{id}", documentId)).andExpect(status().isNotFound());
    }

    @Test void notesSessionsAndKnowledgeBaseDeleteCascadeAndSchemaInitIsIdempotent() throws Exception {
        long kbId = kb();
        long sessionId = session(kbId);
        upload(kbId, "notes.txt", "知识检索".getBytes(StandardCharsets.UTF_8), 201);
        postJson("/api/sessions/" + sessionId + "/messages", Map.of("question", "知识检索"), 200);
        long noteId = postJson("/api/notes", Map.of("kbId", kbId, "title", "复习", "body", "原始笔记"), 201).path("id").asLong();
        request(put("/api/notes/{id}", noteId).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("title", "更新", "body", "新的笔记"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body").value("新的笔记"));
        request(get("/api/notes").param("kbId", "" + kbId)).andExpect(jsonPath("$[0].title").value("更新"));
        request(get("/api/sessions").param("kbId", "" + kbId)).andExpect(jsonPath("$[0].kbId").value(kbId));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(jdbc.getDataSource());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notes", Long.class)).isEqualTo(1);
        request(delete("/api/kbs/{id}", kbId)).andExpect(status().isNoContent());
        for (String table : new String[]{"documents", "chunks", "learning_sessions", "messages", "notes"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).as(table).isZero();
        }
        request(get("/api/sessions/{id}/messages", sessionId)).andExpect(status().isNotFound());
        request(delete("/api/notes/{id}", noteId)).andExpect(status().isNotFound());
    }

    @Test void individualSessionAndNoteDeletionWorks() throws Exception {
        long kbId = kb();
        long sessionId = session(kbId);
        postJson("/api/sessions/" + sessionId + "/messages", Map.of("question", "empty"), 200);
        request(delete("/api/sessions/{id}", sessionId)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messages", Long.class)).isZero();
        long noteId = postJson("/api/notes", Map.of("kbId", kbId, "title", "标题", "body", ""), 201).path("id").asLong();
        request(delete("/api/notes/{id}", noteId)).andExpect(status().isNoContent());
        request(get("/api/notes").param("kbId", "" + kbId)).andExpect(content().json("[]"));
    }

    @Test void duplicateNamesAreFriendlyAndSqlInputIsOnlyData() throws Exception {
        String name = "学习'); DROP TABLE notes; --";
        JsonNode created = postJson("/api/kbs", Map.of("name", name), 201);
        assertThat(created.path("name").asText()).isEqualTo(name);
        JsonNode conflict = postJson("/api/kbs", Map.of("name", name), 409);
        assertThat(conflict.path("code").asText()).isEqualTo("NAME_CONFLICT");
        assertThat(conflict.toString()).doesNotContain("INSERT", "SELECT", "Jdbc", "stackTrace");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notes", Long.class)).isZero();
    }

    @Test void filenamesAreMetadataAndUtf8BomIsAccepted() throws Exception {
        long kbId = kb();
        JsonNode document = upload(kbId, "../../path\\学习.txt", "\uFEFF学习知识".getBytes(StandardCharsets.UTF_8), 201);
        assertThat(document.path("name").asText()).isEqualTo("学习.txt");
        assertThat(jdbc.queryForObject("SELECT extracted_text FROM documents", String.class)).isEqualTo("学习知识");
    }

    @Test void localLibrariesRejectChangedModeModelAndChunkProfiles() throws Exception {
        long kbId = kb();
        long docId = upload(kbId, "facts.txt", "知识检索".getBytes(StandardCharsets.UTF_8), 201).path("id").asLong();
        jdbc.update("UPDATE knowledge_bases SET vector_model='different-model' WHERE id=?", kbId);
        JsonNode mismatch = postJson("/api/kbs/" + kbId + "/search", Map.of("query", "知识检索"), 409);
        assertThat(mismatch.path("code").asText()).isEqualTo("VECTOR_PROFILE_MISMATCH");
        upload(kbId, "another.txt", "知识检索".getBytes(StandardCharsets.UTF_8), 409);
        jdbc.update("UPDATE knowledge_bases SET vector_model='local-hash-zh-bigram-en-v1', vector_mode='embedding' WHERE id=?", kbId);
        postJson("/api/kbs/" + kbId + "/search", Map.of("query", "知识检索"), 409);
        jdbc.update("UPDATE knowledge_bases SET vector_mode='local_hash' WHERE id=?", kbId);
        jdbc.update("UPDATE chunks SET vector_dimension=3 WHERE document_id=?", docId);
        postJson("/api/kbs/" + kbId + "/search", Map.of("query", "知识检索"), 409);
        request(delete("/api/documents/{id}", docId)).andExpect(status().isNoContent());
        upload(kbId, "reimport.txt", "知识检索".getBytes(StandardCharsets.UTF_8), 201);
    }

    @Test void missingResourcesAndMalformedRequestsUseJsonErrors() throws Exception {
        request(get("/api/kbs/99999/documents")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        postJson("/api/kbs/99999/search", Map.of("query", "test"), 404);
        postJson("/api/sessions", Map.of("kbId", 99999, "title", "test"), 404);
        postJson("/api/notes", Map.of("kbId", 99999, "title", "test", "body", ""), 404);
        upload(99999, "missing.txt", "text".getBytes(StandardCharsets.UTF_8), 404);
        request(get("/api/sessions")).andExpect(status().isBadRequest());
        request(get("/api/notes").param("kbId", "-1")).andExpect(status().isBadRequest());
        request(get("/api/kbs/not-a-number/documents")).andExpect(status().isBadRequest());
        request(post("/api/kbs").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.trace").doesNotExist());
        request(post("/api/kbs").contentType(MediaType.TEXT_PLAIN).content("bad"))
                .andExpect(status().isUnsupportedMediaType());
        request(get("/api/unknown")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").exists());
        request(patch("/api/kbs")).andExpect(status().isMethodNotAllowed());
        request(multipart("/api/kbs/1/documents")).andExpect(status().isBadRequest());
    }

    static Stream<Arguments> invalidUploads() throws Exception {
        return Stream.of(
                Arguments.of("empty.txt", new byte[0], 422, "为空"),
                Arguments.of("blank.md", " \n\t".getBytes(StandardCharsets.UTF_8), 422, "有效文本"),
                Arguments.of("bad.txt", new byte[]{(byte) 0xc3, 0x28}, 422, "UTF-8"),
                Arguments.of("binary.txt", new byte[]{0, 1, 2}, 422, "二进制"),
                Arguments.of("image.png", new byte[]{1}, 415, "仅支持"),
                Arguments.of("bad.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8), 422, "PDF"),
                Arguments.of("scan.pdf", TestDocuments.pdf(1, false, false), 422, "OCR"),
                Arguments.of("locked.pdf", TestDocuments.pdf(1, true, true), 422, "加密"),
                Arguments.of("long.pdf", TestDocuments.pdf(51, false, false), 422, "50"),
                Arguments.of("long.txt", "a".repeat(200001).getBytes(StandardCharsets.UTF_8), 422, "200000"),
                Arguments.of("large.txt", new byte[TextProcessor.MAX_BYTES + 1], 413, "10MB")
        );
    }
    @ParameterizedTest @MethodSource("invalidUploads")
    void invalidImportsAreRejectedAtomically(String name, byte[] bytes, int expectedStatus, String message) throws Exception {
        long kbId = kb();
        JsonNode error = upload(kbId, name, bytes, expectedStatus);
        assertThat(error.path("message").asText()).contains(message);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chunks", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT vector_mode FROM knowledge_bases WHERE id=?", String.class, kbId)).isNull();
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("/api/kbs", Map.of("name", " ")),
                Arguments.of("/api/kbs", Map.of("name", "a".repeat(101))),
                Arguments.of("/api/kbs", Map.of("name", "ok", "unknown", "not allowed")),
                Arguments.of("/api/kbs/1/search", Map.of("query", " ")),
                Arguments.of("/api/kbs/1/search", Map.of("query", "a".repeat(4001))),
                Arguments.of("/api/kbs/1/search", Map.of("query", "valid", "topK", 0)),
                Arguments.of("/api/kbs/1/search", Map.of("query", "valid", "topK", 11)),
                Arguments.of("/api/sessions", Map.of("kbId", -1, "title", "title")),
                Arguments.of("/api/sessions", Map.of("kbId", 1, "title", "a".repeat(101))),
                Arguments.of("/api/sessions/1/messages", Map.of("question", " ")),
                Arguments.of("/api/sessions/1/messages", Map.of("question", "a".repeat(4001))),
                Arguments.of("/api/notes", Map.of("kbId", 1, "title", "title", "body", "a".repeat(20001))),
                Arguments.of("/api/notes", Map.of("kbId", 1, "title", " ", "body", "text"))
        );
    }
    @ParameterizedTest @MethodSource("invalidBodies")
    void requestValidationIsConsistent(String path, Object body) throws Exception {
        JsonNode error = postJson(path, body, 400);
        assertThat(error.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.size()).isEqualTo(2);
    }
}
