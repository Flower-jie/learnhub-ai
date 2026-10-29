package com.flower.learnhub;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextAndVectorTest {
    private final TextProcessor processor = new TextProcessor();
    private final LocalVectorizer vectorizer = new LocalVectorizer();

    @Test void chunksAre800CharactersWith100Overlap() {
        String text = "知识学习检索".repeat(400);
        var chunks = processor.split(List.of(new TextProcessor.PageText(null, text)));
        assertThat(chunks).hasSize(4);
        assertThat(chunks.getFirst().text()).hasSize(800);
        assertThat(chunks.get(1).text()).startsWith(chunks.getFirst().text().substring(700));
        assertThat(chunks.get(3).text()).isEqualTo(text.substring(2100));
        assertThat(chunks).extracting(TextProcessor.TextChunk::ordinal).containsExactly(1, 2, 3, 4);
        assertThat(chunks).allMatch(chunk -> chunk.page() == null);
    }

    @Test void chunksNeverCrossPagesAndPreservePageNumbers() {
        var chunks = processor.split(List.of(new TextProcessor.PageText(1, "first page"),
                new TextProcessor.PageText(2, "  "), new TextProcessor.PageText(3, "last page")));
        assertThat(chunks).extracting(TextProcessor.TextChunk::page).containsExactly(1, 3);
        assertThat(chunks).extracting(TextProcessor.TextChunk::text).containsExactly("first page", "last page");
    }

    @Test void splittingDoesNotBreakSurrogatePairs() {
        String text = "a".repeat(799) + "\uD840\uDC00".repeat(60) + "b".repeat(900);
        var chunks = processor.split(List.of(new TextProcessor.PageText(null, text)));
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(Character.isLowSurrogate(chunk.text().charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1))).isFalse();
            assertThat(chunk.text().length()).isLessThanOrEqualTo(800);
        });
    }

    @Test void totalCharacterLimitIsEnforcedBeforeChunking() {
        assertThatThrownBy(() -> processor.split(List.of(new TextProcessor.PageText(null, "a".repeat(200001)))))
                .isInstanceOf(ApiException.class).hasMessageContaining("200000");
    }

    @Test void chunkCountHasAnIndependentLimit() {
        var pages = java.util.stream.IntStream.range(0, 401).mapToObj(i -> new TextProcessor.PageText(i + 1, "text")).toList();
        assertThatThrownBy(() -> processor.split(pages)).isInstanceOf(ApiException.class).hasMessageContaining("400");
    }

    @Test void emptyTextCreatesNoChunks() {
        assertThat(processor.split(List.of(new TextProcessor.PageText(null, " \n\r\t")))).isEmpty();
    }

    @Test void chineseBigramsAndEnglishWordsAreExplicitTerms() {
        assertThat(vectorizer.terms("知识学习 Spring BOOT 21")).containsExactly("知识", "识学", "学习", "spring", "boot", "21");
    }

    @Test void hashVectorsAreStableAndL2Normalized() {
        double[] a = vectorizer.vector("知识学习 Spring spring");
        assertThat(a).hasSize(LocalVectorizer.DIMENSION).containsExactly(vectorizer.vector("知识学习 Spring spring"));
        assertThat(LocalVectorizer.cosine(a, a)).isCloseTo(1.0, within(1e-10));
        assertThat(java.util.Arrays.stream(a).map(v -> v * v).sum()).isCloseTo(1.0, within(1e-10));
    }

    @Test void relevantTextRanksHigherAndUnrelatedTermsAreNotShared() {
        double[] query = vectorizer.vector("知识检索");
        assertThat(LocalVectorizer.cosine(query, vectorizer.vector("知识检索是一种学习方式")))
                .isGreaterThan(LocalVectorizer.cosine(query, vectorizer.vector("apple banana ocean")));
        assertThat(vectorizer.hasSharedTerm("知识检索", "apple banana ocean")).isFalse();
        assertThat(LocalVectorizer.cosine(vectorizer.vector("!?!"), query)).isZero();
    }

    @Test void dimensionMismatchAndNonfiniteVectorsAreRejected() {
        assertThatThrownBy(() -> LocalVectorizer.cosine(new double[2], new double[3]))
                .isInstanceOf(ApiException.class).hasMessageContaining("维度");
        assertThatThrownBy(() -> LocalVectorizer.normalize(new double[]{Double.NaN})).isInstanceOf(ApiException.class);
    }
}
