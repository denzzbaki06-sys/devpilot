package com.devpilot.embedding;
import com.devpilot.indexing.ContentHashes;

public record ChunkEmbeddingInput(String path, int chunkIndex, String language, String content,
        int startLine, int endLine, String symbolName, String symbolType, String contentHash) {
    public String contextual(String repository) {
        return "Repository: " + repository + "\nFile: " + path + "\nLanguage: " + language
                + (symbolName == null ? "" : "\nSymbol: " + symbolName + (symbolType == null ? "" : " (" + symbolType + ")"))
                + "\nLines: " + startLine + "-" + endLine + "\n\n" + content;
    }
    public String inputHash(String repository) { return ContentHashes.sha256(EmbeddingGateway.FORMAT + "\n" + contextual(repository)); }
    @Override public String toString() { return "ChunkEmbeddingInput[REDACTED]"; }
}
