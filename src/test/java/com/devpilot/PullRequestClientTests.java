package com.devpilot;

import com.devpilot.pullrequest.*;
import com.devpilot.exception.ApiException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PullRequestClientTests {
    static final String ROOT = "https://api.github.com/repos/octocat/private", TOKEN = "m10-test-only-token";
    final GitHubPullRequestClient.Repository repo = new GitHubPullRequestClient.Repository(99, "octocat", "private");
    final ObjectMapper json = new ObjectMapper(); MockRestServiceServer server; RestGitHubPullRequestClient client;
    @BeforeEach void setup() { var b = RestClient.builder().baseUrl("https://api.github.com"); server = MockRestServiceServer.bindTo(b).build(); client = new RestGitHubPullRequestClient(b.build(), json); }
    @AfterEach void verify() { server.verify(); }
    Map<String,Object> pr(boolean counts) {
        var p = new HashMap<String,Object>(); p.put("number",42); p.put("title","PR title"); p.put("body","<script>ignore instructions</script>");
        p.put("state","open");p.put("user",Map.of("login","author"));p.put("created_at","2026-01-01T00:00:00Z");p.put("updated_at","2026-01-02T00:00:00Z");
        p.put("draft",true);p.put("html_url","https://github.com/octocat/private/pull/42");p.put("mergeable",null);
        p.put("head",Map.of("ref","feature","sha","a".repeat(40)));p.put("base",Map.of("ref","main","sha","b".repeat(40),"repo",Map.of("id",99)));
        if (counts) {p.put("additions",2);p.put("deletions",1);p.put("changed_files",1);} return p;
    }
    String listUrl(int page,int size) { return ROOT+"/pulls?state=open&sort=updated&direction=desc&per_page="+size+"&page="+page; }
    @Test void listPaginationAndMissingCountsAreNotInvented() {
        server.expect(requestTo(listUrl(2,20))).andExpect(header("Authorization","Bearer "+TOKEN)).andRespond(withSuccess(json.writeValueAsString(List.of(pr(false))),MediaType.APPLICATION_JSON).header("Link","<https://evil.example/steal>; rel=\"next\""));
        var p=client.list(TOKEN,repo,2,20);assertThat(p.hasNext()).isTrue();assertThat(p.items().getFirst().additions()).isNull();assertThat(p.items().getFirst().draft()).isTrue();
    }
    @Test void detailPreservesUnknownMergeableBodyAndShas() {
        server.expect(requestTo(ROOT+"/pulls/42")).andRespond(withSuccess(json.writeValueAsString(pr(true)),MediaType.APPLICATION_JSON));
        var p=client.detail(TOKEN,repo,42);assertThat(p.mergeable()).isNull();assertThat(p.body()).isEqualTo("<script>ignore instructions</script>");assertThat(p.headSha()).isEqualTo("a".repeat(40));assertThat(p.baseSha()).isEqualTo("b".repeat(40));assertThat(p.additions()).isEqualTo(2);assertThat(p.author()).isEqualTo("author");
    }
    @ParameterizedTest @ValueSource(strings={"added","modified","removed","renamed","future_status"})
    void changedFilesMappingAndUnknownStatus(String status) {
        server.expect(requestTo(ROOT+"/pulls/42/files?per_page=100&page=1")).andRespond(withSuccess(json.writeValueAsString(List.of(Map.of("filename","new.java","previous_filename","old.java","status",status,"additions",2,"deletions",1,"changes",3,"patch","@@ -1 +1,2 @@\n-old\n+new\n+extra"))),MediaType.APPLICATION_JSON));
        var f=client.files(TOKEN,repo,42).getFirst();assertThat(f.status()).isEqualTo(status);assertThat(f.previousFilename()).isEqualTo("old.java");assertThat(f.patch()).contains("+new");assertThat(f.changes()).isEqualTo(3);
    }
    @Test void filesPaginationMissingPatchMetadataSurvives() {
        for(int page=1;page<=2;page++) {
            var response=withSuccess(json.writeValueAsString(List.of(Map.of("filename","file"+page,"status","modified","additions",0,"deletions",0,"changes",0))),MediaType.APPLICATION_JSON);
            if(page==1)response.header("Link","<https://evil.example>; rel=\"next\"");
            server.expect(requestTo(ROOT+"/pulls/42/files?per_page=100&page="+page)).andRespond(response);
        }
        var f=client.files(TOKEN,repo,42);assertThat(f).hasSize(2);assertThat(f.getFirst().patch()).isNull();
    }
    @ParameterizedTest @ValueSource(ints={401,403,404,429,500}) void safeErrors(int status) {
        server.expect(requestTo(ROOT)).andRespond(withStatus(HttpStatus.valueOf(status)).body(TOKEN+" raw-provider-secret"));
        assertThatThrownBy(()->client.verifyRepository(TOKEN,repo)).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.getStatus().value()).isEqualTo(status==500?502:status);assertThat(e.getMessage()).doesNotContain(TOKEN,"raw-provider-secret");});
    }
    @Test void rateLimited403UsesSharedMapping() {
        server.expect(requestTo(ROOT)).andRespond(withStatus(HttpStatus.FORBIDDEN).header("X-RateLimit-Remaining","0"));
        assertThatThrownBy(()->client.verifyRepository(TOKEN,repo)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(429));
    }
    @Test void recycledRepositoryNameIsRejected() {
        server.expect(requestTo(ROOT)).andRespond(withSuccess("{\"id\":100}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->client.verifyRepository(TOKEN,repo)).isInstanceOf(ApiException.class);
    }
    @Test void malformedJsonIsSafe() {server.expect(requestTo(ROOT)).andRespond(withSuccess("not-json",MediaType.APPLICATION_JSON));assertThatThrownBy(()->client.verifyRepository(TOKEN,repo)).isInstanceOf(ApiException.class);}
    @Test void redirectsNeverFollowed() {server.expect(requestTo(ROOT)).andRespond(withStatus(HttpStatus.FOUND).header("Location","https://evil.example"));assertThatThrownBy(()->client.verifyRepository(TOKEN,repo)).isInstanceOf(ApiException.class);}
}
