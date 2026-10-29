package com.flower.learnhub;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AppSettingsTest {
    static final String TOKEN = "test-only-access-token-never-use-in-production";

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"short", "                        ", "this-token-has-a space-in-it"})
    void tokenIsMandatoryAndMustBeStrongEnough(String token) {
        assertThatThrownBy(() -> new AppSettings(token, "", "", "", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("APP_ACCESS_TOKEN");
    }

    @Test void unconfiguredProvidersStayLocalAndRedacted() {
        var settings = new AppSettings(TOKEN, null, null, null, null, null, null);
        assertThat(settings.retrievalMode()).isEqualTo("local_hash");
        assertThat(settings.chatEnabled()).isFalse();
        assertThat(settings.toString()).doesNotContain(TOKEN);
    }

    @ParameterizedTest @ValueSource(strings = {"http://example.com/v1", "file:///tmp/test", "https://user:pass@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/v1#fragment"})
    void unsafeProviderUrlsFailFastWithoutEchoingTheUrl(String base) {
        assertThatThrownBy(() -> new AppSettings(TOKEN, base, "test-only-key", "test-model", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(base);
    }

    @ParameterizedTest @ValueSource(strings = {"https://example.com/v1", "http://127.0.0.1:1234/v1", "http://localhost:1234/v1", "http://[::1]:1234/v1"})
    void httpsAndLoopbackHttpAreAccepted(String base) {
        var settings = new AppSettings(TOKEN, base, "test-only-key", "test-model", "", "", "");
        assertThat(settings.retrievalMode()).isEqualTo("embedding");
        assertThat(settings.toString()).doesNotContain("test-only-key");
    }

    @Test void partiallyConfiguredProvidersStayDisabled() {
        var settings = new AppSettings(TOKEN, "https://example.com/v1", "test-only-key", "", "https://example.com/v1", "", "test-chat");
        assertThat(settings.embeddingEnabled()).isFalse();
        assertThat(settings.chatEnabled()).isFalse();
        assertThat(settings.retrievalMode()).isEqualTo("local_hash");
    }
}
