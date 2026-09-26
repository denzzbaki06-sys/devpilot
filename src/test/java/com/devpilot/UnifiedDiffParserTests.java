package com.devpilot;

import com.devpilot.pullrequest.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class UnifiedDiffParserTests {
    final UnifiedDiffParser parser = new UnifiedDiffParser();
    @Test void contextAdditionDeletionLineNumbers() {
        var r = parser.parse("@@ -10,4 +10,6 @@ method\n keep\n-old\n+new\n+extra\n+more\n tail\n end");
        assertThat(r.status()).isEqualTo(UnifiedDiffParser.Status.PARSED);
        var lines = r.hunks().getFirst().lines();
        assertThat(lines.get(0).oldLineNumber()).isEqualTo(10); assertThat(lines.get(0).newLineNumber()).isEqualTo(10);
        assertThat(lines.get(1).oldLineNumber()).isEqualTo(11); assertThat(lines.get(1).newLineNumber()).isNull();
        assertThat(lines.get(2).oldLineNumber()).isNull(); assertThat(lines.get(2).newLineNumber()).isEqualTo(11);
        assertThat(lines.get(5).oldLineNumber()).isEqualTo(12); assertThat(lines.get(5).newLineNumber()).isEqualTo(14);
    }
    @Test void addedFileZeroOldCount() { var h = parser.parse("@@ -0,0 +1,2 @@\n+a\n+b").hunks().getFirst(); assertThat(h.oldCount()).isZero(); assertThat(h.lines().getLast().newLineNumber()).isEqualTo(2); }
    @Test void removedFileZeroNewCount() { var h = parser.parse("@@ -1,2 +0,0 @@\n-a\n-b").hunks().getFirst(); assertThat(h.newCount()).isZero(); assertThat(h.lines().getLast().oldLineNumber()).isEqualTo(2); }
    @Test void omittedCountsAndMultipleHunks() {
        var r = parser.parse("@@ -1 +1 @@\n-a\n+b\n@@ -20 +25 @@\n-c\n+d\n");
        assertThat(r.hunks()).hasSize(2); assertThat(r.hunks().get(1).lines().getLast().newLineNumber()).isEqualTo(25);
    }
    @Test void noNewlineMarkersDoNotAdvanceCounters() {
        var r = parser.parse("@@ -1 +1 @@\n-old\n\\ No newline at end of file\n+new\n\\ No newline at end of file");
        assertThat(r.hunks().getFirst().lines()).hasSize(2); assertThat(r.hunks().getFirst().lines().getLast().newLineNumber()).isEqualTo(1);
    }
    @Test void missingAndBlankPatchUnavailable() { assertThat(parser.parse(null).status()).isEqualTo(UnifiedDiffParser.Status.UNAVAILABLE); assertThat(parser.parse("").hunks()).isEmpty(); }
    @Test void partialPatchDiscardsAllMappings() { bad("@@ -1 +1 @@\n a\n@@ -4,2 +4,2 @@\n b"); }
    @Test void malformedHeader() { bad("@@ broken @@\n+a"); }
    @Test void excessLines() { bad("@@ -0,0 +1 @@\n+a\n+b"); }
    @Test void integerOverflow() { bad("@@ -2147483647,2 +1 @@\n-a\n-b\n+c"); }
    @Test void orphanMarker() { bad("@@ -1 +1 @@\n\\ No newline at end of file\n x"); }
    @Test void overlappingHunks() { bad("@@ -2 +2 @@\n x\n@@ -2 +2 @@\n x"); }
    @Test void prefixAndWhitespaceRemainData() {
        var r = parser.parse("@@ -0,0 +1,2 @@\n++<script>alert(1)</script>\n+  ");
        assertThat(r.hunks().getFirst().lines().getFirst().content()).isEqualTo("+<script>alert(1)</script>");
        assertThat(r.hunks().getFirst().lines().getLast().content()).isEqualTo("  ");
    }
    private void bad(String patch) { var r = parser.parse(patch); assertThat(r.status()).isEqualTo(UnifiedDiffParser.Status.MALFORMED); assertThat(r.hunks()).isEmpty(); }
}
