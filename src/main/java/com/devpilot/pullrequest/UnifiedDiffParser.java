package com.devpilot.pullrequest;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class UnifiedDiffParser {
    public enum LineType { CONTEXT, ADDITION, DELETION }
    public enum Status { PARSED, UNAVAILABLE, MALFORMED }
    public record DiffLine(LineType type, String content, Integer oldLineNumber, Integer newLineNumber) {
        @Override public String toString() { return "DiffLine[REDACTED]"; }
    }
    public record DiffHunk(int oldStart, int oldCount, int newStart, int newCount, List<DiffLine> lines) {}
    public record Result(Status status, List<DiffHunk> hunks) {}
    private static final Pattern HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?:.*)$");
    public Result parse(String patch) {
        if (patch == null || patch.isBlank()) return new Result(Status.UNAVAILABLE, List.of());
        try {
            String[] lines = patch.split("\n", -1); int end = lines.length;
            if (lines[end - 1].isEmpty()) end--;
            var hunks = new ArrayList<DiffHunk>(); int cursor = 0; long previousOldEnd = -1, previousNewEnd = -1;
            while (cursor < end) {
                var header = HEADER.matcher(lines[cursor++]); if (!header.matches()) return malformed();
                int oldStart = Integer.parseInt(header.group(1)), oldCount = count(header.group(2));
                int newStart = Integer.parseInt(header.group(3)), newCount = count(header.group(4));
                if ((oldCount > 0 && oldStart == 0) || (newCount > 0 && newStart == 0)
                        || oldStart < previousOldEnd || newStart < previousNewEnd) return malformed();
                long oldLine = oldStart, newLine = newStart;
                long oldEnd = oldLine + oldCount, newEnd = newLine + newCount;
                if (oldEnd > Integer.MAX_VALUE || newEnd > Integer.MAX_VALUE) return malformed();
                var parsed = new ArrayList<DiffLine>(); boolean markerAllowed = false;
                while (cursor < end && !lines[cursor].startsWith("@@")) {
                    String line = lines[cursor++];
                    if (line.equals("\\ No newline at end of file")) {
                        if (!markerAllowed) return malformed(); markerAllowed = false; continue;
                    }
                    if (line.isEmpty()) return malformed();
                    String content = line.substring(1);
                    switch (line.charAt(0)) {
                        case ' ' -> parsed.add(new DiffLine(LineType.CONTEXT, content, (int) oldLine++, (int) newLine++));
                        case '+' -> parsed.add(new DiffLine(LineType.ADDITION, content, null, (int) newLine++));
                        case '-' -> parsed.add(new DiffLine(LineType.DELETION, content, (int) oldLine++, null));
                        default -> { return malformed(); }
                    }
                    markerAllowed = true;
                    if (oldLine > oldEnd || newLine > newEnd) return malformed();
                }
                if (oldLine != oldEnd || newLine != newEnd || parsed.isEmpty()) return malformed();
                hunks.add(new DiffHunk(oldStart, oldCount, newStart, newCount, List.copyOf(parsed)));
                previousOldEnd = oldEnd; previousNewEnd = newEnd;
            }
            return new Result(Status.PARSED, List.copyOf(hunks));
        } catch (NumberFormatException ex) { return malformed(); }
    }
    private int count(String value) { return value == null ? 1 : Integer.parseInt(value); }
    private Result malformed() { return new Result(Status.MALFORMED, List.of()); }
}
