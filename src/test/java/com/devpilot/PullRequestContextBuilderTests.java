package com.devpilot;
import com.devpilot.pullrequest.*;
import com.devpilot.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PullRequestContextBuilderTests {
    final PullRequestContextBuilder builder = new PullRequestContextBuilder(new UnifiedDiffParser());
    @Test void completeHunkButTruncatedFileCannotProduceCitations() {
        var c=builder.build(1,null,List.of(new GitHubPullRequestClient.File("file","modified",null,20,0,20,"@@ -0,0 +1 @@\n+only-one")));
        assertThat(c.files().getFirst().parseStatus()).isEqualTo(UnifiedDiffParser.Status.MALFORMED);
        assertThat(c.files().getFirst().hunks()).isEmpty();assertThat(c.statistics().malformedPatchCount()).isEqualTo(1);
    }
    @Test void largeContextRejectedNeverTruncated() {
        assertThatThrownBy(()->builder.build(1,null,List.of(new GitHubPullRequestClient.File("file","added",null,200001,0,200001,"+x\n".repeat(200001)))))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(413));
    }
    @Test void unknownStatusAndMissingPatchPreserveMetadata() {
        var c=builder.build(1,null,List.of(new GitHubPullRequestClient.File("new","future_status","old",1,2,3,null)));
        assertThat(c.files().getFirst().status()).isEqualTo("future_status");assertThat(c.files().getFirst().previousFilename()).isEqualTo("old");
        assertThat(c.statistics().totalPatchChars()).isZero();assertThat(c.statistics().patchUnavailableCount()).isEqualTo(1);
    }
}
