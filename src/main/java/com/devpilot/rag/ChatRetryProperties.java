package com.devpilot.rag;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("devpilot.ai.chat.retry")
public record ChatRetryProperties(int maxRetries, int delayMillis) {
    public ChatRetryProperties { if(maxRetries<0 || maxRetries>3 || delayMillis<0 || delayMillis>5000) throw new IllegalArgumentException("Invalid chat retry limits"); }
}
