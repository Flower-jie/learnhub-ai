package com.flower.learnhub;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public final class ApiModels {
    private ApiModels() {}

    public record Config(String retrievalMode, boolean chatEnabled, String embeddingModel) {}
    public record KnowledgeBase(long id, String name, long documentCount, long chunkCount) {}
    public record Document(long id, String name, long chunkCount, OffsetDateTime createdAt) {}
    public record Session(long id, long kbId, String title, OffsetDateTime createdAt) {}
    public record Note(long id, long kbId, String title, String body, OffsetDateTime createdAt) {}
    public record Citation(long documentId, String documentName, long chunkId, Integer page,
                           int ordinal, String text, double score, boolean sourceAvailable) {}
    public record SearchResult(String retrievalMode, List<Citation> hits) {}
    public record Message(long id, String role, String content, String mode,
                          List<Citation> citations, OffsetDateTime createdAt) {}
    public record CreateKnowledgeBase(@NotBlank @Size(max = 100) String name) {}
    public record Search(@NotBlank @Size(max = 4000) String query, @Min(1) @Max(10) Integer topK) {
        public int limit() { return topK == null ? 5 : topK; }
    }
    public record CreateSession(@NotNull @Positive Long kbId, @NotBlank @Size(max = 100) String title) {}
    public record Question(@NotBlank @Size(max = 4000) String question) {}
    public record CreateNote(@NotNull @Positive Long kbId, @NotBlank @Size(max = 100) String title,
                             @NotNull @Size(max = 20000) String body) {}
    public record UpdateNote(@NotBlank @Size(max = 100) String title, @NotNull @Size(max = 20000) String body) {}
    public record Error(String code, String message) {}
}
