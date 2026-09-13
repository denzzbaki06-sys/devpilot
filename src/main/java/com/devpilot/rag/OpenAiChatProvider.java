package com.devpilot.rag;

import com.devpilot.exception.ApiException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import tools.jackson.databind.ObjectMapper;

@Component
@EnableConfigurationProperties({ChatProperties.class, RagProperties.class})
public class OpenAiChatProvider implements LlmProvider {
    private final ChatProperties properties;
    private final RestClient client;
    private final ObjectMapper json;
    @Autowired public OpenAiChatProvider(ChatProperties properties, ObjectMapper json) {
        this(properties, client(properties), json);
    }
    public OpenAiChatProvider(ChatProperties properties, RestClient client, ObjectMapper json) {
        this.properties = properties; this.client = client; this.json = json;
    }
    private static RestClient client(ChatProperties p) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(Math.clamp(p.timeoutSeconds(), 1, 120)));
        return RestClient.builder().requestFactory(factory).build();
    }
    public void requireConfigured() { properties.validate(); }
    public String model() { return properties.model(); }
    public static ApiException invalid() { return new ApiException(HttpStatus.BAD_GATEWAY, "Chat provider returned an invalid grounded response"); }
    public Completion complete(String systemInstruction, String question, String context) {
        requireConfigured();
        var statement = Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("text", Map.of("type", "string"), "sourceIds", Map.of("type", "array", "items", Map.of("type", "integer"))),
                "required", List.of("text", "sourceIds"));
        var schema = Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("statements", Map.of("type", "array", "items", statement), "insufficientContext", Map.of("type", "boolean")),
                "required", List.of("statements", "insufficientContext"));
        var body = Map.of("model", model(), "max_completion_tokens", 8192,
                "messages", List.of(Map.of("role", "system", "content", systemInstruction),
                        Map.of("role", "user", "content", json.writeValueAsString(Map.of("question", question, "repositoryContext", context)))),
                "response_format", Map.of("type", "json_schema", "json_schema", Map.of("name", "grounded_answer", "strict", true, "schema", schema)));
        try {
            byte[] bytes = client.post().uri(properties.baseUrl().replaceAll("/+$", "") + "/chat/completions")
                    .header("Authorization", "Bearer " + properties.apiKey()).contentType(MediaType.APPLICATION_JSON).body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Chat provider rate limited; retry later");
                        if (status < 200 || status >= 300) throw new ApiException(HttpStatus.BAD_GATEWAY, "Chat provider request failed");
                        byte[] result = response.getBody().readNBytes(262145);
                        if (result.length > 262144) throw invalid();
                        return result;
                    });
            var root = json.readTree(bytes);
            if (root == null || !root.path("choices").isArray() || root.path("choices").size() != 1) throw invalid();
            var choice = root.path("choices").get(0);
            if (!"stop".equals(choice.path("finish_reason").asText()) || !choice.path("message").path("content").isTextual()) throw invalid();
            var data = json.readTree(choice.path("message").path("content").asText());
            if (data == null || !data.path("statements").isArray() || data.path("statements").size() > 30
                    || !data.path("insufficientContext").isBoolean()) throw invalid();
            var statements = new ArrayList<Statement>();
            for (var item : data.path("statements")) {
                if (!item.path("text").isTextual() || !item.path("sourceIds").isArray() || item.path("sourceIds").size() > 20) throw invalid();
                var ids = new ArrayList<Integer>();
                for (var id : item.path("sourceIds")) {
                    if (!id.isIntegralNumber() || !id.canConvertToInt()) throw invalid();
                    ids.add(id.asInt());
                }
                statements.add(new Statement(item.path("text").asText(), List.copyOf(ids)));
            }
            // Report configured model identity; provider-supplied metadata never controls the API response.
            return new Completion(List.copyOf(statements), data.path("insufficientContext").asBoolean(), model());
        } catch (ResourceAccessException ex) { throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "Chat provider timed out or is unavailable"); }
        catch (RestClientException | tools.jackson.core.JacksonException ex) { throw invalid(); }
    }
}
