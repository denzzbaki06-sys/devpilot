package com.devpilot.indexing;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.StringWriter;
import java.net.URI;
import java.util.*;
import java.util.regex.*;
import javax.tools.*;
import org.springframework.stereotype.Component;

@Component
public class CodeChunker {
    public record Chunk(int chunkIndex, String content, int startLine, int endLine,
                        String symbolName, String symbolType, String contentHash) {
        @Override public String toString() { return "CodeChunk[REDACTED]"; }
    }
    private record Symbol(int start, int end, String name, String type) {}
    private static final Pattern PYTHON = Pattern.compile("^(\\s*)(?:async\\s+)?(def|class)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern SCRIPT = Pattern.compile("(?:^|\\s)(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?(function|class)\\s+([A-Za-z_$][\\w$]*)|(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=.*=>");
    private final IndexingProperties properties;
    public CodeChunker(IndexingProperties properties) { this.properties = properties; }

    public List<Chunk> chunk(String content, String language) {
        var lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        if (content.endsWith("\n")) lines.removeLast();
        if (lines.isEmpty()) return List.of();
        List<Symbol> symbols;
        try {
            symbols = switch (language) {
                case "JAVA" -> javaSymbols(content);
                case "PYTHON" -> pythonSymbols(lines);
                case "JAVASCRIPT", "JAVASCRIPT_REACT", "TYPESCRIPT", "TYPESCRIPT_REACT" -> scriptSymbols(content);
                default -> List.of();
            };
        } catch (RuntimeException | StackOverflowError ex) { symbols = List.of(); }
        if (symbols.size() > 1000) symbols = List.of(); // Bound symbol partitioning cost for generated-looking input.
        // Partition the file at every symbol boundary. Nested methods take precedence over
        // their enclosing class, so class chunks do not repeat all of the method bodies.
        var boundaries = new TreeSet<Integer>(); boundaries.add(1); boundaries.add(lines.size() + 1);
        for (Symbol s : symbols) {
            if (s.start >= 1 && s.end <= lines.size() && s.end >= s.start) {
                boundaries.add(s.start); boundaries.add(s.end + 1);
            }
        }
        var points = new ArrayList<>(boundaries);
        var result = new ArrayList<Chunk>();
        for (int i = 0; i < points.size() - 1; i++) {
            int start = points.get(i), end = points.get(i + 1) - 1;
            Symbol symbol = symbols.stream().filter(s -> s.start <= start && s.end >= end)
                    .min(Comparator.comparingInt(s -> s.end - s.start)).orElse(null);
            for (int first = start; first <= end;) {
                int last = Math.min(end, first + properties.chunkLines() - 1);
                String text = String.join("\n", lines.subList(first - 1, last));
                if (!text.isBlank() && result.size() >= properties.maxChunks()) {
                    throw new com.devpilot.exception.ApiException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                            "File exceeds configured chunk count limit");
                }
                if (!text.isBlank()) result.add(new Chunk(result.size(), text, first, last,
                        symbol == null ? null : symbol.name, symbol == null ? null : symbol.type, ContentHashes.sha256(text)));
                if (last == end) break;
                first = last + 1 - properties.chunkOverlapLines();
            }
        }
        return result;
    }
    private List<Symbol> javaSymbols(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return List.of();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///Source.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(new StringWriter(), manager, diagnostics,
                    List.of("-proc:none"), null, List.of(file));
            var units = task.parse(); // Parse only: no analysis, compilation, processors, or execution.
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) return List.of();
            var positions = Trees.instance(task).getSourcePositions();
            var symbols = new ArrayList<Symbol>();
            for (var unit : units) {
                new TreeScanner<Void, String>() {
                    private void add(Tree node, String name, String type) {
                        long start = positions.getStartPosition(unit, node), end = positions.getEndPosition(unit, node);
                        if (start >= 0 && end > start) symbols.add(new Symbol((int) unit.getLineMap().getLineNumber(start),
                                (int) unit.getLineMap().getLineNumber(end - 1), name, type));
                    }
                    @Override public Void visitClass(ClassTree node, String parent) {
                        String name = node.getSimpleName().toString();
                        if (!name.isBlank()) add(node, name, node.getKind().name());
                        return super.visitClass(node, name);
                    }
                    @Override public Void visitMethod(MethodTree node, String parent) {
                        boolean constructor = node.getReturnType() == null;
                        add(node, constructor ? parent : node.getName().toString(), constructor ? "CONSTRUCTOR" : "METHOD");
                        return super.visitMethod(node, parent);
                    }
                }.scan(unit, null);
            }
            return symbols;
        } catch (java.io.IOException ex) { return List.of(); }
    }
    private List<Symbol> pythonSymbols(List<String> lines) {
        var result = new ArrayList<Symbol>();
        for (int i = 0; i < lines.size(); i++) {
            var matcher = PYTHON.matcher(lines.get(i));
            if (!matcher.find()) continue;
            int indent = matcher.group(1).replace("\t", "    ").length(), end = lines.size();
            for (int j = i + 1; j < lines.size(); j++) {
                String line = lines.get(j);
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                int nextIndent = line.substring(0, line.length() - line.stripLeading().length()).replace("\t", "    ").length();
                if (nextIndent <= indent) { end = j; break; }
            }
            result.add(new Symbol(i + 1, end, matcher.group(3), matcher.group(2).equals("class") ? "CLASS" : "FUNCTION"));
        }
        return result;
    }
    private List<Symbol> scriptSymbols(String source) {
        // Mask strings/comments without changing offsets or line numbers before matching braces.
        char[] masked = source.toCharArray();
        char quote = 0; boolean lineComment = false, blockComment = false, escaped = false;
        for (int i = 0; i < masked.length; i++) {
            char c = source.charAt(i), next = i + 1 < masked.length ? source.charAt(i + 1) : 0;
            if (lineComment) { if (c == '\n') lineComment = false; else masked[i] = ' '; continue; }
            if (blockComment) {
                if (c != '\n') masked[i] = ' ';
                if (c == '*' && next == '/') { masked[++i] = ' '; blockComment = false; }
                continue;
            }
            if (quote != 0) {
                if (c != '\n') masked[i] = ' ';
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '/' && next == '/') { masked[i] = masked[++i] = ' '; lineComment = true; }
            else if (c == '/' && next == '*') { masked[i] = masked[++i] = ' '; blockComment = true; }
            else if (c == '\'' || c == '"' || c == '`') { quote = c; masked[i] = ' '; }
        }
        if (quote != 0 || blockComment) return List.of();
        String clean = new String(masked);
        int[] lineAt = new int[masked.length + 1]; int line = 1;
        for (int i = 0; i < masked.length; i++) { lineAt[i] = line; if (masked[i] == '\n') line++; }
        lineAt[masked.length] = line;
        var closing = new HashMap<Integer, Integer>(); var stack = new ArrayDeque<Integer>();
        for (int i = 0; i < masked.length; i++) {
            if (masked[i] == '{') stack.push(i);
            if (masked[i] == '}') { if (stack.isEmpty()) return List.of(); closing.put(stack.pop(), i); }
        }
        if (!stack.isEmpty()) return List.of();
        var result = new ArrayList<Symbol>(); int offset = 0;
        for (String row : clean.split("\n", -1)) {
            var m = SCRIPT.matcher(row);
            if (m.find()) {
                int begin = offset + m.start(), brace = clean.indexOf('{', offset + m.start());
                int semicolon = clean.indexOf(';', offset + m.start());
                if (brace >= 0 && (semicolon < 0 || brace < semicolon) && closing.containsKey(brace)) {
                    result.add(new Symbol(lineAt[begin], lineAt[closing.get(brace)],
                            m.group(3) != null ? m.group(3) : m.group(2), "class".equals(m.group(1)) ? "CLASS" : "FUNCTION"));
                }
            }
            offset += row.length() + 1;
        }
        return result;
    }
}
