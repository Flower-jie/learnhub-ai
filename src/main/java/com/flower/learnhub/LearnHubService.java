package com.flower.learnhub;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional(readOnly = true)
public class LearnHubService {
    private static final int MAX_KB_CHUNKS = 5000;
    private final LearnHubStore store;
    private final TextProcessor textProcessor;
    private final LocalVectorizer local;
    private final AiClient ai;
    private final AppSettings settings;

    public LearnHubService(LearnHubStore store, TextProcessor textProcessor, LocalVectorizer local, AiClient ai, AppSettings settings) {
        this.store = store;
        this.textProcessor = textProcessor;
        this.local = local;
        this.ai = ai;
        this.settings = settings;
    }

    public ApiModels.Config config() {
        return new ApiModels.Config(settings.retrievalMode(), settings.chatEnabled(), settings.embeddingEnabled() ? settings.embeddingModel() : "");
    }
    public List<ApiModels.KnowledgeBase> knowledgeBases() { return store.knowledgeBases(); }
    @Transactional public ApiModels.KnowledgeBase createKnowledgeBase(String name) { return store.createKnowledgeBase(name.strip()); }
    @Transactional public void deleteKnowledgeBase(long id) { store.profile(id, true); store.deleteKnowledgeBase(id); }
    public List<ApiModels.Document> documents(long kbId) { store.profile(kbId, false); return store.documents(kbId); }

    @Transactional
    public ApiModels.Document importDocument(long kbId, MultipartFile file) {
        LearnHubStore.Profile profile = store.profile(kbId, true);
        checkMode(profile);
        TextProcessor.Extracted extracted = textProcessor.extract(file);
        if (store.knowledgeBase(kbId).chunkCount() + extracted.chunks().size() > MAX_KB_CHUNKS) {
            throw new ApiException(409, "LIBRARY_LIMIT", "每个知识库最多支持 5000 分块，请新建知识库");
        }
        List<String> texts = extracted.chunks().stream().map(TextProcessor.TextChunk::text).toList();
        List<double[]> vectors = vectors(texts);
        int dimension = vectors.getFirst().length;
        if (profile.dimension() != null && profile.dimension() != dimension) throw ApiException.profileMismatch();
        store.setProfile(kbId, settings.retrievalMode(), settings.vectorModel(), dimension);
        long id = store.insertDocument(kbId, extracted);
        for (int i = 0; i < extracted.chunks().size(); i++) {
            if (vectors.get(i).length != dimension) throw ApiException.profileMismatch();
            store.insertChunk(id, extracted.chunks().get(i), vectors.get(i), settings.retrievalMode(), settings.vectorModel());
        }
        return store.document(id);
    }

    @Transactional
    public void deleteDocument(long id) {
        long kbId = store.documentKnowledgeBase(id);
        store.profile(kbId, true);
        store.deleteDocument(id);
        store.clearEmptyProfile(kbId);
    }

    public ApiModels.SearchResult search(long kbId, String query, int topK) {
        LearnHubStore.Profile profile = store.profile(kbId, false);
        checkMode(profile);
        if (profile.dimension() == null) return new ApiModels.SearchResult(settings.retrievalMode(), List.of());
        double[] queryVector = vectors(List.of(query)).getFirst();
        if (queryVector.length != profile.dimension()) throw ApiException.profileMismatch();
        Comparator<ApiModels.Citation> bestFirst = Comparator.comparingDouble(ApiModels.Citation::score).reversed()
                .thenComparingLong(ApiModels.Citation::chunkId);
        PriorityQueue<ApiModels.Citation> best = new PriorityQueue<>(topK, bestFirst.reversed());
        store.eachChunk(kbId, chunk -> {
            if (!settings.retrievalMode().equals(chunk.mode()) || !settings.vectorModel().equals(chunk.model())
                    || chunk.dimension() != queryVector.length || chunk.vector().length != queryVector.length) {
                throw ApiException.profileMismatch();
            }
            if (!settings.embeddingEnabled() && !local.hasSharedTerm(query, chunk.text())) return;
            double score = LocalVectorizer.cosine(queryVector, chunk.vector());
            double threshold = settings.embeddingEnabled() ? 0.35 : 0.10;
            if (!Double.isFinite(score) || score < threshold) return;
            best.offer(new ApiModels.Citation(chunk.documentId(), chunk.documentName(), chunk.id(), chunk.page(),
                    chunk.ordinal(), chunk.text(), score, true));
            if (best.size() > topK) best.poll();
        });
        return new ApiModels.SearchResult(settings.retrievalMode(), best.stream().sorted(bestFirst).toList());
    }

    private void checkMode(LearnHubStore.Profile profile) {
        if (profile.mode() != null && (!Objects.equals(profile.mode(), settings.retrievalMode())
                || !Objects.equals(profile.model(), settings.vectorModel()))) throw ApiException.profileMismatch();
    }
    private List<double[]> vectors(List<String> texts) {
        return settings.embeddingEnabled() ? ai.embeddings(texts) : texts.stream().map(local::vector).toList();
    }

    public List<ApiModels.Session> sessions(long kbId) { store.profile(kbId, false); return store.sessions(kbId); }
    @Transactional public ApiModels.Session createSession(long kbId, String title) {
        store.profile(kbId, true);
        return store.createSession(kbId, title.strip());
    }
    @Transactional public void deleteSession(long id) {
        ApiModels.Session session = store.session(id, false);
        store.profile(session.kbId(), true);
        store.deleteSession(id);
    }
    public List<ApiModels.Message> messages(long sessionId) { store.session(sessionId, false); return store.messages(sessionId, false); }

    @Transactional
    public ApiModels.Message ask(long sessionId, String question) {
        ApiModels.Session session = store.session(sessionId, false);
        // All writes lock the knowledge base first, then the session, so evidence cannot disappear mid-answer.
        store.profile(session.kbId(), true);
        store.session(sessionId, true);
        List<ApiModels.Citation> citations = search(session.kbId(), question, 5).hits();
        String mode = "retrieval_only";
        String answer = null;
        if (settings.chatEnabled() && !citations.isEmpty()) {
            answer = ai.chat(question, citations, store.messages(sessionId, true));
            mode = "rag";
        }
        // Both rows are inserted only after generation succeeds, within the same transaction.
        store.insertMessage(sessionId, "user", question, "user", List.of());
        return store.insertMessage(sessionId, "assistant", answer, mode, citations);
    }

    public List<ApiModels.Note> notes(long kbId) { store.profile(kbId, false); return store.notes(kbId); }
    @Transactional public ApiModels.Note createNote(long kbId, String title, String body) {
        store.profile(kbId, true);
        return store.createNote(kbId, title.strip(), body);
    }
    @Transactional public ApiModels.Note updateNote(long id, String title, String body) {
        store.profile(store.note(id).kbId(), true);
        return store.updateNote(id, title.strip(), body);
    }
    @Transactional public void deleteNote(long id) {
        store.profile(store.note(id).kbId(), true);
        store.deleteNote(id);
    }
}
