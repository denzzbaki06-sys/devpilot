package com.devpilot.indexing;
import java.util.*;

public class IndexingStats {
    public int filesDiscovered, filesIndexed, filesSkipped, chunksCreated;
    public long lineCount;
    public String sourceCommit;
    public int embeddingsCreated, embeddingsReused;
    public String embeddingModel;
    public final Map<String, Integer> languages = new TreeMap<>();
    public final Map<String, Integer> skipReasons = new TreeMap<>();
    public void skip(String reason) { filesSkipped++; skipReasons.merge(reason, 1, Integer::sum); }
    public void indexed(String language, int lines, int chunks) {
        filesIndexed++; lineCount += lines; chunksCreated += chunks; languages.merge(language, 1, Integer::sum);
    }
}
