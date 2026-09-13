package com.devpilot;

import com.devpilot.indexing.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodeChunkingTests {
    static IndexingProperties properties(int lines, int overlap) {
        return new IndexingProperties(500000, lines, overlap, 20000, 50000000, 20000, 100000, 1800);
    }
    final SourceFilePolicy policy = new SourceFilePolicy(properties(100, 15));
    final CodeChunker chunker = new CodeChunker(properties(100, 15));
    @Test void javaMethodsConstructorsAndInterfacesAreSeparated() {
        String source = "interface Greeting {\n void hello();\n}\nclass Hello {\n Hello() { }\n void first() {\n  int value = 1;\n }\n void second() {\n  int value = 2;\n }\n}\n";
        var chunks = chunker.chunk(source, "JAVA");
        assertThat(chunks).anyMatch(c -> "CONSTRUCTOR".equals(c.symbolType()) && "Hello".equals(c.symbolName()));
        assertThat(chunks).anyMatch(c -> "INTERFACE".equals(c.symbolType()));
        var first = chunks.stream().filter(c -> "first".equals(c.symbolName())).findFirst().orElseThrow();
        assertThat(first.startLine()).isEqualTo(6); assertThat(first.endLine()).isEqualTo(8);
        assertThat(first.content()).doesNotContain("second");
        assertThat(chunks).anyMatch(c -> "second".equals(c.symbolName()));
    }
    @Test void javascriptFunctionsAreSeparated() {
        var chunks = chunker.chunk("export function first() {\n return '}';\n}\nfunction second() {\n return 2;\n}\n", "JAVASCRIPT");
        assertThat(chunks).anyMatch(c -> "first".equals(c.symbolName()) && c.endLine() == 3);
        assertThat(chunks).anyMatch(c -> "second".equals(c.symbolName()) && c.startLine() == 4);
    }
    @Test void typescriptArrowFunctionRecognized() {
        var chunks = chunker.chunk("const hello = (name: string) => {\n return name;\n};\n", "TYPESCRIPT");
        assertThat(chunks).anyMatch(c -> "hello".equals(c.symbolName()) && "FUNCTION".equals(c.symbolType()));
    }
    @Test void pythonClassesAndFunctionsRecognized() {
        var chunks = chunker.chunk("class Hello:\n    def first(self):\n        return 1\n\ndef second():\n    return 2\n", "PYTHON");
        assertThat(chunks).anyMatch(c -> "CLASS".equals(c.symbolType()));
        assertThat(chunks).anyMatch(c -> "first".equals(c.symbolName()));
        assertThat(chunks).anyMatch(c -> "second".equals(c.symbolName()) && c.startLine() == 5);
    }
    @Test void fallbackHasConfiguredOverlapAndCorrectRanges() {
        String text = String.join("\n", IntStream.rangeClosed(1, 220).mapToObj(i -> "line " + i).toList());
        var chunks = chunker.chunk(text, "MARKDOWN");
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).startLine()).isEqualTo(1); assertThat(chunks.get(0).endLine()).isEqualTo(100);
        assertThat(chunks.get(1).startLine()).isEqualTo(86); assertThat(chunks.get(1).endLine()).isEqualTo(185);
        assertThat(chunks.get(2).startLine()).isEqualTo(171); assertThat(chunks.get(2).endLine()).isEqualTo(220);
    }
    @Test void malformedJavaUsesFallback() {
        var chunks = chunker.chunk("class Broken {\n void incomplete(\n", "JAVA");
        assertThat(chunks).hasSize(1); assertThat(chunks.getFirst().symbolName()).isNull();
    }
    @Test void malformedScriptUsesFallback() {
        var chunks = chunker.chunk("function broken() {\n return 1;\n", "JAVASCRIPT");
        assertThat(chunks).hasSize(1); assertThat(chunks.getFirst().symbolName()).isNull();
    }
    @Test void longMethodIsSplitWithinItsOwnBoundary() {
        var small = new CodeChunker(properties(10, 2));
        String code = "class LongMethod {\n void run() {\n" + "  int x = 1;\n".repeat(30) + " }\n}\n";
        var methods = small.chunk(code, "JAVA").stream().filter(c -> "run".equals(c.symbolName())).toList();
        assertThat(methods.size()).isGreaterThan(1);
        assertThat(methods).allMatch(c -> c.endLine() - c.startLine() + 1 <= 10);
    }
    @Test void binaryAndMalformedUtf8AreSkipped() {
        assertThat(policy.normalize(new byte[]{65, 0, 66}).skipReason()).isEqualTo("BINARY");
        assertThat(policy.normalize(new byte[]{(byte) 0xc3, 0x28}).skipReason()).isEqualTo("INVALID_UTF8");
    }
    @Test void secretPathsAreSkipped() {
        for (String path : List.of(".env", ".env.local", "config/credentials.json", "secrets/config.yaml", "keys/private.pem", "key.p12", ".ssh/id_rsa", "keystore.jks")) {
            assertThat(policy.skipReason(path, "100644", 10)).as(path).isNotNull();
        }
    }
    @Test void oversizedFilesAreSkippedBeforeDownload() { assertThat(policy.skipReason("Large.java", "100644", 500001)).isEqualTo("OVERSIZED"); }
    @Test void dependencyGeneratedAndMinifiedPathsAreSkipped() {
        for (String path : List.of("node_modules/pkg/a.js", "target/Foo.java", "dist/app.js", "build/generated/Foo.java", "vendor/a.py", ".git/config", "src/generated/Foo.java", "a.min.js", "a.min.css", "a.js.map", "package-lock.json")) {
            assertThat(policy.skipReason(path, "100644", 10)).as(path).isNotNull();
        }
    }
    @Test void hashesAreDeterministic() {
        assertThat(ContentHashes.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        var a = chunker.chunk("hello\nworld", "MARKDOWN"); var b = chunker.chunk("hello\nworld", "MARKDOWN");
        assertThat(a).isEqualTo(b); assertThat(a.getFirst().contentHash()).isEqualTo(ContentHashes.sha256(a.getFirst().content()));
    }
    @Test void normalizationPreservesWhitespaceAndLineNumbers() {
        var text = policy.normalize("a  \r\n\r\n  b\r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(text.content()).isEqualTo("a  \n\n  b\n"); assertThat(text.lineCount()).isEqualTo(3);
        assertThat(chunker.chunk(text.content(), "MARKDOWN").getFirst().endLine()).isEqualTo(3);
    }
    @Test void supportedExtensionsHaveDeterministicLanguages() {
        for (String extension : List.of("java", "js", "jsx", "ts", "tsx", "html", "css", "scss", "json", "yml", "yaml", "properties", "xml", "sql", "md", "py", "sh")) {
            assertThat(policy.language("src/code." + extension)).isNotNull();
            assertThat(policy.skipReason("src/code." + extension, "100644", 10)).isNull();
        }
    }
    @Test void symlinkSubmoduleAndUnsafePathsAreSkipped() {
        assertThat(policy.skipReason("a.java", "120000", 10)).isEqualTo("SYMLINK_OR_SUBMODULE");
        assertThat(policy.skipReason("a.java", "160000", 10)).isEqualTo("SYMLINK_OR_SUBMODULE");
        for (String path : List.of("../a.java", "/a.java", "a/../b.java", "a\\b.java")) assertThat(policy.skipReason(path, "100644", 10)).isEqualTo("UNSAFE_PATH");
    }
    @Test void privateKeyContentAndLfsPointerAreSkipped() {
        assertThat(policy.normalize("-----BEGIN RSA PRIVATE KEY-----".getBytes(StandardCharsets.UTF_8)).skipReason()).isEqualTo("SENSITIVE_CONTENT");
        assertThat(policy.normalize("version https://git-lfs.github.com/spec/v1\n".getBytes(StandardCharsets.UTF_8)).skipReason()).isEqualTo("LFS_POINTER");
    }
    @Test void invalidOverlapRejectedAtStartup() {
        assertThatThrownBy(() -> properties(10, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void chunkBudgetIsEnforcedDuringGeneration() {
        var config = new IndexingProperties(500000, 1, 0, 20000, 50000000, 20000, 2, 1800);
        assertThatThrownBy(() -> new CodeChunker(config).chunk("a\nb\nc", "MARKDOWN"))
                .isInstanceOf(com.devpilot.exception.ApiException.class);
    }
    @Test void pathologicalOverlapIsRejected() {
        assertThatThrownBy(() -> properties(100, 99)).isInstanceOf(IllegalArgumentException.class);
    }
}
