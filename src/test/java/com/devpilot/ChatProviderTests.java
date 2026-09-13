package com.devpilot;
import com.devpilot.rag.*;
import com.devpilot.exception.ApiException;
import com.devpilot.embedding.SemanticSearchStore.Result;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ChatProviderTests {
    ObjectMapper json = new ObjectMapper();
    MockRestServiceServer server;
    OpenAiChatProvider provider;
    ChatProperties properties(String key) { return new ChatProperties("openai", key, "https://api.openai.com/v1", "test-model", 60); }
    @BeforeEach void setup() {
        var builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build();
        provider = new OpenAiChatProvider(properties("test-only-key"), builder.build(), json);
    }
    @AfterEach void verify() { server.verify(); }
    String envelope(String content, String reason) {
        return json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", reason, "message", Map.of("content", content)))));
    }
    @Test void structuredCompletionUsesSeparateSystemAndContext() {
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-only-key"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("json_schema")))
                .andRespond(withSuccess(envelope("{\"statements\":[{\"text\":\"Answer\",\"sourceIds\":[1]}],\"insufficientContext\":false}", "stop"), MediaType.APPLICATION_JSON));
        var result = provider.complete("Policy", "Question", "SOURCE 1");
        assertThat(result.statements().getFirst().sourceIds()).containsExactly(1);
        assertThat(result.model()).isEqualTo("test-model");
    }
    @Test void missingKeyIsLazyAndRedacted() {
        var absent = new OpenAiChatProvider(properties(""), RestClient.create(), json);
        assertThatThrownBy(absent::requireConfigured).isInstanceOf(ApiException.class).hasMessageContaining("not configured");
        assertThat(properties("secret-value").toString()).doesNotContain("secret-value");
    }
    @Test void providerErrorDoesNotLeakBody() {
        server.expect(requestTo("https://api.openai.com/v1/chat/completions")).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("secret-value"));
        assertThatThrownBy(() -> provider.complete("s", "q", "c")).isInstanceOf(ApiException.class).hasMessage("Chat provider request failed");
    }
    @Test void truncatedAnswerIsRejected() {
        server.expect(requestTo("https://api.openai.com/v1/chat/completions")).andRespond(withSuccess(envelope("{}", "length"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.complete("s", "q", "c")).isInstanceOf(ApiException.class).hasMessageContaining("invalid grounded response");
    }
    @Test void malformedCitationsAreRejected() {
        server.expect(requestTo("https://api.openai.com/v1/chat/completions")).andRespond(withSuccess(envelope("{\"statements\":[{\"text\":\"x\",\"sourceIds\":[1.5]}],\"insufficientContext\":false}", "stop"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.complete("s", "q", "c")).isInstanceOf(ApiException.class);
    }
    @Test void contextBudgetIncludesMetadataAndKeepsExactChunks() {
        var a = new Result(1L, "file.java", "JAVA", "symbol", "METHOD", 3, 4, "line1\nline2", .9);
        var b = new Result(2L, "other.java", "JAVA", null, null, 1, 1, "x".repeat(1000), .8);
        var builder = new RagContextBuilder();
        var context = builder.build(List.of(b, a, a), 256);
        assertThat(context.text().length()).isLessThanOrEqualTo(256);
        assertThat(context.sources()).containsExactly(a);
        assertThat(context.text()).contains("Lines: 3-4", a.content());
        assertThat(builder.build(List.of(b), 256).sources()).isEmpty();
    }
}
