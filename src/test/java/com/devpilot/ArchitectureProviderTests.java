package com.devpilot;
import com.devpilot.rag.*;
import com.devpilot.architecture.ask.*;
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
class ArchitectureProviderTests {
    MockRestServiceServer server;OpenAiChatProvider provider;
    final String url="https://api.openai.com/v1/chat/completions";
    @BeforeEach void setup(){var client=RestClient.builder();server=MockRestServiceServer.bindTo(client).build();provider=new OpenAiChatProvider(new ChatProperties("openai","local-test-only-key", "https://api.openai.com/v1","test-model",60),client.build(),new ObjectMapper(),new ChatRetryProperties(2,0));}
    @AfterEach void verify(){server.verify();}
    void run(){provider.completeStructured(ArchitectureQuestionService.POLICY,Map.of("untrustedRepository",Map.of("sources",Map.of("S1","source"))),"architecture_answer",ArchitectureAnswerValidator.schema());}
    @ParameterizedTest @ValueSource(ints={429,500,503}) void architectureUsesExistingBoundedRetryTransport(int status){
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.valueOf(status)));
        server.expect(requestTo(url)).andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"insufficientContext\\\":true,\\\"statements\\\":[]}\"}}]}",MediaType.APPLICATION_JSON));run();
    }
    @Test void timeoutUsesSafe504AfterBoundedRetries(){for(int i=0;i<3;i++)server.expect(requestTo(url)).andRespond(withException(new SocketTimeoutException("private-upstream-detail")));assertThatThrownBy(this::run).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.getStatus().value()).isEqualTo(504);assertThat(e.getMessage()).doesNotContain("private-upstream-detail");});}
}
