package com.devpilot.rag;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import com.devpilot.exception.ApiException;
import org.springframework.http.HttpStatus;

@ConfigurationProperties("devpilot.ai.chat")
public record ChatProperties(String provider, String apiKey, String baseUrl, String model, int timeoutSeconds) {
    public void validate() {
        try {
            URI uri = URI.create(baseUrl);
            boolean local = "http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
            if (!("openai".equals(provider) || "openai-compatible".equals(provider)) || uri.getHost() == null
                    || !("https".equals(uri.getScheme()) || local) || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || model == null || model.isBlank() || model.length() > 255
                    || timeoutSeconds < 1 || timeoutSeconds > 120) throw new IllegalArgumentException();
        } catch (RuntimeException ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Chat provider configuration is invalid"); }
        if (apiKey == null || apiKey.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Chat API key is not configured");
    }
    @Override public String toString() { return "ChatProperties[REDACTED]"; }
}
