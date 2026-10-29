package com.flower.learnhub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class LearnHubStore {
    public record Profile(long id, String mode, String model, Integer dimension) {}
    public record StoredChunk(long id, long documentId, String documentName, Integer page, int ordinal,
                              String text, double[] vector, String mode, String model, int dimension) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final TypeReference<List<ApiModels.Citation>> CITATIONS = new TypeReference<>() {};
    private static final String KB_SELECT = """
            SELECT k.id, k.name, (SELECT COUNT(*) FROM documents d WHERE d.kb_id=k.id) document_count,
            (SELECT COUNT(*) FROM chunks c JOIN documents d ON c.document_id=d.id WHERE d.kb_id=k.id) chunk_count
            FROM knowledge_bases k
            """;
    private static final String DOCUMENT_SELECT = """
            SELECT d.id, d.name, d.created_at, (SELECT COUNT(*) FROM chunks c WHERE c.document_id=d.id) chunk_count
            FROM documents d
            """;
    private static final RowMapper<ApiModels.KnowledgeBase> KB_MAPPER = (rs, n) -> new ApiModels.KnowledgeBase(
            rs.getLong("id"), rs.getString("name"), rs.getLong("document_count"), rs.getLong("chunk_count"));
    private static final RowMapper<ApiModels.Document> DOCUMENT_MAPPER = (rs, n) -> new ApiModels.Document(
            rs.getLong("id"), rs.getString("name"), rs.getLong("chunk_count"), time(rs));
    private static final RowMapper<ApiModels.Session> SESSION_MAPPER = (rs, n) -> new ApiModels.Session(
            rs.getLong("id"), rs.getLong("kb_id"), rs.getString("title"), time(rs));
    private static final RowMapper<ApiModels.Note> NOTE_MAPPER = (rs, n) -> new ApiModels.Note(
            rs.getLong("id"), rs.getLong("kb_id"), rs.getString("title"), rs.getString("body"), time(rs));

    public LearnHubStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    private static OffsetDateTime time(ResultSet rs) throws SQLException { return rs.getObject("created_at", OffsetDateTime.class); }
    private static <T> T required(List<T> rows) { if (rows.isEmpty()) throw ApiException.missing(); return rows.getFirst(); }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("Missing generated ID");
        return key.longValue();
    }

    public List<ApiModels.KnowledgeBase> knowledgeBases() { return jdbc.query(KB_SELECT + " ORDER BY k.id DESC", KB_MAPPER); }
    public ApiModels.KnowledgeBase knowledgeBase(long id) { return required(jdbc.query(KB_SELECT + " WHERE k.id=?", KB_MAPPER, id)); }
    public ApiModels.KnowledgeBase createKnowledgeBase(String name) {
        return knowledgeBase(insert("INSERT INTO knowledge_bases(name) VALUES (?)", name));
    }
    public void deleteKnowledgeBase(long id) {
        if (jdbc.update("DELETE FROM knowledge_bases WHERE id=?", id) == 0) throw ApiException.missing();
    }
    public Profile profile(long id, boolean lock) {
        return required(jdbc.query("SELECT id, vector_mode, vector_model, vector_dimension FROM knowledge_bases WHERE id=?"
                        + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new Profile(rs.getLong("id"), rs.getString("vector_mode"), rs.getString("vector_model"),
                        rs.getObject("vector_dimension", Integer.class)), id));
    }
    public void setProfile(long id, String mode, String model, int dimension) {
        jdbc.update("UPDATE knowledge_bases SET vector_mode=?, vector_model=?, vector_dimension=? WHERE id=?", mode, model, dimension, id);
    }
    public void clearEmptyProfile(long id) {
        if (knowledgeBase(id).chunkCount() == 0) {
            jdbc.update("UPDATE knowledge_bases SET vector_mode=NULL, vector_model=NULL, vector_dimension=NULL WHERE id=?", id);
        }
    }
    public List<ApiModels.Document> documents(long kbId) {
        return jdbc.query(DOCUMENT_SELECT + " WHERE d.kb_id=? ORDER BY d.id DESC", DOCUMENT_MAPPER, kbId);
    }
    public ApiModels.Document document(long id) { return required(jdbc.query(DOCUMENT_SELECT + " WHERE d.id=?", DOCUMENT_MAPPER, id)); }
    public long documentKnowledgeBase(long id) {
        return required(jdbc.query("SELECT kb_id FROM documents WHERE id=?", (rs, n) -> rs.getLong(1), id));
    }
    public long insertDocument(long kbId, TextProcessor.Extracted extracted) {
        return insert("INSERT INTO documents(kb_id, name, extracted_text) VALUES (?,?,?)", kbId, extracted.name(), extracted.text());
    }
    public void insertChunk(long documentId, TextProcessor.TextChunk chunk, double[] vector, String mode, String model) {
        jdbc.update("""
                INSERT INTO chunks(document_id, ordinal, page_number, chunk_text, vector_json, vector_mode, vector_model, vector_dimension)
                VALUES (?,?,?,?,?,?,?,?)
                """, documentId, chunk.ordinal(), chunk.page(), chunk.text(), json(vector), mode, model, vector.length);
    }
    public void deleteDocument(long id) {
        if (jdbc.update("DELETE FROM documents WHERE id=?", id) == 0) throw ApiException.missing();
    }
    public void eachChunk(long kbId, Consumer<StoredChunk> consumer) {
        jdbc.query("""
                SELECT c.*, d.name document_name FROM chunks c JOIN documents d ON c.document_id=d.id
                WHERE d.kb_id=? ORDER BY c.id
                """, (RowCallbackHandler) rs -> consumer.accept(new StoredChunk(rs.getLong("id"), rs.getLong("document_id"),
                rs.getString("document_name"), rs.getObject("page_number", Integer.class), rs.getInt("ordinal"),
                rs.getString("chunk_text"), vector(rs.getString("vector_json")), rs.getString("vector_mode"),
                rs.getString("vector_model"), rs.getInt("vector_dimension"))), kbId);
    }

    public List<ApiModels.Session> sessions(long kbId) {
        return jdbc.query("SELECT * FROM learning_sessions WHERE kb_id=? ORDER BY id DESC", SESSION_MAPPER, kbId);
    }
    public ApiModels.Session session(long id, boolean lock) {
        return required(jdbc.query("SELECT * FROM learning_sessions WHERE id=?" + (lock ? " FOR UPDATE" : ""), SESSION_MAPPER, id));
    }
    public ApiModels.Session createSession(long kbId, String title) {
        return session(insert("INSERT INTO learning_sessions(kb_id, title) VALUES (?,?)", kbId, title), false);
    }
    public void deleteSession(long id) {
        if (jdbc.update("DELETE FROM learning_sessions WHERE id=?", id) == 0) throw ApiException.missing();
    }
    public List<ApiModels.Message> messages(long sessionId, boolean recentOnly) {
        List<ApiModels.Message> rows = jdbc.query("SELECT * FROM messages WHERE session_id=? ORDER BY id "
                        + (recentOnly ? "DESC LIMIT 6" : "ASC"), (rs, n) -> message(rs), sessionId);
        if (recentOnly) {
            rows = new ArrayList<>(rows);
            java.util.Collections.reverse(rows);
        }
        return rows;
    }
    public ApiModels.Message insertMessage(long sessionId, String role, String content, String mode, List<ApiModels.Citation> citations) {
        // Store identifiers, not a second copy of source text. Deleted evidence cannot leak via historical citations.
        List<ApiModels.Citation> references = citations.stream().map(c -> new ApiModels.Citation(c.documentId(), c.documentName(),
                c.chunkId(), c.page(), c.ordinal(), "", c.score(), true)).toList();
        long id = insert("INSERT INTO messages(session_id, role, content, mode, citations_json) VALUES (?,?,?,?,?)",
                sessionId, role, content, mode, json(references));
        return required(jdbc.query("SELECT * FROM messages WHERE id=?", (rs, n) -> message(rs), id));
    }
    private ApiModels.Message message(ResultSet rs) throws SQLException {
        return new ApiModels.Message(rs.getLong("id"), rs.getString("role"), rs.getString("content"), rs.getString("mode"),
                resolveCitations(rs.getString("citations_json")), time(rs));
    }
    private List<ApiModels.Citation> resolveCitations(String value) {
        List<ApiModels.Citation> references;
        try { references = mapper.readValue(value, CITATIONS); }
        catch (Exception e) { throw new IllegalStateException("Stored citation data is invalid"); }
        return references.stream().map(c -> {
            List<String> text = jdbc.query("SELECT chunk_text FROM chunks WHERE id=? AND document_id=?",
                    (rs, n) -> rs.getString(1), c.chunkId(), c.documentId());
            return new ApiModels.Citation(c.documentId(), c.documentName(), c.chunkId(), c.page(), c.ordinal(),
                    text.isEmpty() ? "" : text.getFirst(), c.score(), !text.isEmpty());
        }).toList();
    }

    public List<ApiModels.Note> notes(long kbId) { return jdbc.query("SELECT * FROM notes WHERE kb_id=? ORDER BY id DESC", NOTE_MAPPER, kbId); }
    public ApiModels.Note note(long id) { return required(jdbc.query("SELECT * FROM notes WHERE id=?", NOTE_MAPPER, id)); }
    public ApiModels.Note createNote(long kbId, String title, String body) {
        return note(insert("INSERT INTO notes(kb_id, title, body) VALUES (?,?,?)", kbId, title, body));
    }
    public ApiModels.Note updateNote(long id, String title, String body) {
        if (jdbc.update("UPDATE notes SET title=?, body=? WHERE id=?", title, body, id) == 0) throw ApiException.missing();
        return note(id);
    }
    public void deleteNote(long id) { if (jdbc.update("DELETE FROM notes WHERE id=?", id) == 0) throw ApiException.missing(); }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Data serialization failed"); }
    }
    private double[] vector(String value) {
        try { return mapper.readValue(value, double[].class); }
        catch (Exception e) { throw new IllegalStateException("Stored vector data is invalid"); }
    }
}
