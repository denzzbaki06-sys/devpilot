package com.devpilot.indexing;

import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class SourceFilePolicy {
    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("java", "JAVA"), Map.entry("js", "JAVASCRIPT"), Map.entry("jsx", "JAVASCRIPT_REACT"),
            Map.entry("ts", "TYPESCRIPT"), Map.entry("tsx", "TYPESCRIPT_REACT"), Map.entry("html", "HTML"),
            Map.entry("css", "CSS"), Map.entry("scss", "SCSS"), Map.entry("json", "JSON"), Map.entry("yml", "YAML"),
            Map.entry("yaml", "YAML"), Map.entry("properties", "PROPERTIES"), Map.entry("xml", "XML"),
            Map.entry("sql", "SQL"), Map.entry("md", "MARKDOWN"), Map.entry("py", "PYTHON"), Map.entry("sh", "SHELL"));
    private static final Set<String> EXCLUDED = Set.of("node_modules", "target", "dist", "build", ".git", "vendor",
            "generated", "generated-sources", "generated-test-sources", "__pycache__", ".next", ".nuxt", "coverage", ".venv", "venv", ".ssh", ".aws");
    private final IndexingProperties properties;
    public SourceFilePolicy(IndexingProperties properties) { this.properties = properties; }
    public String language(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? null : LANGUAGES.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
    public String skipReason(String path, String mode, long size) {
        if (path == null || path.getBytes(StandardCharsets.UTF_8).length > 1024 || path.startsWith("/") || path.contains("\\")
                || path.chars().anyMatch(Character::isISOControl)) return "UNSAFE_PATH";
        for (String segment : path.toLowerCase(Locale.ROOT).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return "UNSAFE_PATH";
            if (EXCLUDED.contains(segment) || segment.endsWith(".generated") || segment.startsWith("generated-") || segment.startsWith("generated_")) return "GENERATED_OR_DEPENDENCY";
            if (segment.equals(".env") || segment.startsWith(".env.") || segment.startsWith("id_rsa")
                    || segment.startsWith("id_ed25519") || segment.matches(".*(?:^|[._-])(?:secrets?|credentials?|keystore)(?:[._-].*|$)")
                    || segment.matches(".*\\.(?:pem|p12|pfx|jks|keystore|key)")) return "SENSITIVE_PATH";
        }
        if (!Set.of("100644", "100755").contains(mode)) return "SYMLINK_OR_SUBMODULE";
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".min.js") || lower.endsWith(".min.css") || lower.endsWith(".map")
                || lower.endsWith("package-lock.json") || lower.endsWith("yarn.lock") || lower.endsWith("pnpm-lock.yaml")) return "GENERATED_OR_LOCKFILE";
        if (size < 0 || size > properties.maxFileSizeBytes()) return "OVERSIZED";
        return language(path) == null ? "UNSUPPORTED" : null;
    }
    public record Text(String content, int lineCount, String skipReason) {
        @Override public String toString() { return "SourceText[REDACTED]"; }
    }
    public Text normalize(byte[] bytes) {
        if (bytes.length > properties.maxFileSizeBytes()) return new Text(null, 0, "OVERSIZED");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) { return new Text(null, 0, "INVALID_UTF8"); }
        if (text.chars().anyMatch(c -> (c < 32 && c != '\n' && c != '\r' && c != '\t') || c == 127)) return new Text(null, 0, "BINARY");
        if (text.contains("-----BEGIN ") && text.contains("PRIVATE KEY-----")) return new Text(null, 0, "SENSITIVE_CONTENT");
        if (text.startsWith("version https://git-lfs.github.com/spec/")) return new Text(null, 0, "LFS_POINTER");
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.isBlank()) return new Text(null, 0, "EMPTY");
        String[] lines = text.split("\n", -1);
        for (String line : lines) if (line.length() > properties.maxLineLength()) return new Text(null, 0, "LONG_LINE");
        // Keep whitespace inside strings and indentation; normalization never removes lines.
        return new Text(text, lines.length - (text.endsWith("\n") ? 1 : 0), null);
    }
}
