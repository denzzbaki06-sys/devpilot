package com.devpilot.rag;
import com.devpilot.embedding.SemanticSearchStore.Result;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RagContextBuilder {
    public record Context(String text, List<Result> sources) {
        @Override public String toString() { return "RagContext[REDACTED]"; }
    }
    public Context build(List<Result> results, int budget) {
        var text = new StringBuilder(); var sources = new ArrayList<Result>(); var seen = new HashSet<Long>();
        for (var chunk : results.stream().sorted(Comparator.comparingDouble(Result::similarity).reversed().thenComparing(Result::chunkId)).toList()) {
            if (!seen.add(chunk.chunkId()) || chunk.content().isBlank()) continue;
            // Whole chunks only: references always describe exactly the original code span.
            String fence = "```";
            while (chunk.content().contains(fence)) fence += "`";
            String block = "SOURCE " + (sources.size() + 1) + "\nFile: " + chunk.path() + "\nLines: " + chunk.startLine() + "-" + chunk.endLine()
                    + "\nLanguage: " + chunk.language() + "\nSymbol: " + Objects.toString(chunk.symbolName(), "")
                    + "\n" + fence + "\n" + chunk.content() + "\n" + fence + "\n\n";
            if (text.length() + block.length() > budget) continue;
            text.append(block); sources.add(chunk);
        }
        return new Context(text.toString(), List.copyOf(sources));
    }
}
