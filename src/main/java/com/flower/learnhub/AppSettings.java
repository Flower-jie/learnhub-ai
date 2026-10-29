package com.flower.learnhub;

import java.net.URI;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppSettings(String accessToken, String embeddingApiBaseUrl, String embeddingApiKey,
                          String embeddingModel, String chatApiBaseUrl, String chatApiKey, String chatModel) {
    public AppSettings {
        if (accessToken == null || accessToken.strip().length() < 24 || accessToken.length() > 4096
                || accessToken.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("APP_ACCESS_TOKEN must contain at least 24 non-whitespace characters");
        }
        embeddingApiBaseUrl = clean(embeddingApiBaseUrl);
        embeddingApiKey = clean(embeddingApiKey);
        embeddingModel = clean(embeddingModel);
        chatApiBaseUrl = clean(chatApiBaseUrl);
        chatApiKey = clean(chatApiKey);
        chatModel = clean(chatModel);
        checkProvider(embeddingApiBaseUrl, embeddingApiKey, embeddingModel, "EMBEDDING");
        checkProvider(chatApiBaseUrl, chatApiKey, chatModel, "CHAT");
    }

    private static String clean(String value) { return value == null ? "" : value.strip(); }

    private static void checkProvider(String base, String key, String model, String label) {
        // Optional providers are enabled only when all three environment variables are present.
        if (base.isEmpty() || key.isEmpty() || model.isEmpty()) return;
        if (model.length() > 200 || key.length() > 4096 || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " configuration is invalid");
        }
        try {
            URI uri = URI.create(base);
            String host = uri.getHost();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    || "[::1]".equals(host) || "::1".equals(host);
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !("https".equals(scheme) || ("http".equals(scheme) && loopback))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + " API_BASE_URL requires HTTPS (HTTP only for loopback)");
        }
    }

    public boolean embeddingEnabled() { return !embeddingApiBaseUrl.isEmpty() && !embeddingApiKey.isEmpty() && !embeddingModel.isEmpty(); }
    public boolean chatEnabled() { return !chatApiBaseUrl.isEmpty() && !chatApiKey.isEmpty() && !chatModel.isEmpty(); }
    public String retrievalMode() { return embeddingEnabled() ? "embedding" : "local_hash"; }
    public String vectorModel() { return embeddingEnabled() ? embeddingModel : "local-hash-zh-bigram-en-v1"; }

    // Configuration objects must never print credentials in logs or diagnostics.
    @Override public String toString() { return "AppSettings[redacted]"; }
}
