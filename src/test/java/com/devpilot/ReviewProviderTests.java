package com.devpilot;
import com.devpilot.rag.*;
import com.devpilot.review.*;
import com.devpilot.exception.ApiException;
import java.util.*;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class ReviewProviderTests {
    final ObjectMapper json=new ObjectMapper();MockRestServiceServer server;OpenAiChatProvider provider;final String url="https://api.openai.com/v1/chat/completions";
    @BeforeEach void setup(){var b=RestClient.builder();server=MockRestServiceServer.bindTo(b).build();provider=new OpenAiChatProvider(new ChatProperties("openai","test-only-key", "https://api.openai.com/v1","test-model",60),b.build(),json,new ChatRetryProperties(2,0));}
    @AfterEach void verify(){server.verify();}
    void ok(String content){server.expect(requestTo(url)).andRespond(withSuccess(json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content))))),MediaType.APPLICATION_JSON));}
    void run(){provider.completeStructured(PullRequestReviewService.POLICY,Map.of("diff","untrusted"),"review",ReviewOutputValidator.schema());}
    @ParameterizedTest @ValueSource(ints={429,500,503}) void retriesTransientErrors(int status){server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.valueOf(status)));ok("{\"findings\":[]}");run();}
    @ParameterizedTest @ValueSource(ints={400,401}) void neverRetriesPermanentError(int status){server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.valueOf(status)).body("test-only-secret"));assertThatThrownBy(this::run).isInstanceOf(ApiException.class).hasMessage("Chat provider request failed");}
    @Test void retryExhaustionSafe(){for(int i=0;i<3;i++)server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));assertThatThrownBy(this::run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(429));}
    @Test void timeoutRetriesBounded(){for(int i=0;i<3;i++)server.expect(requestTo(url)).andRespond(withException(new SocketTimeoutException("test-only-secret")));assertThatThrownBy(this::run).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.getStatus().value()).isEqualTo(504);assertThat(e.getMessage()).doesNotContain("test-only-secret");});}
    @Test void malformedJsonControlled(){ok("{bad");assertThatThrownBy(this::run).isInstanceOf(ApiException.class);}
}
