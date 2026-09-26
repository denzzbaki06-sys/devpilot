package com.devpilot.indexing;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.*;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;
import javax.tools.*;

/** Parse only: never resolve classpaths, run processors, compile or execute repository code. */
public final class JavaSourceParser {
    private JavaSourceParser() {}
    public static boolean parse(String source, BiConsumer<CompilationUnitTree, SourcePositions> visitor) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return false;
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///Source.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return source; }
        };
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(new StringWriter(), manager, diagnostics, List.of("-proc:none"), null, List.of(file));
            var units = task.parse();
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) return false;
            var positions = Trees.instance(task).getSourcePositions();
            units.forEach(unit -> visitor.accept(unit, positions));
            return true;
        } catch (java.io.IOException | RuntimeException | StackOverflowError ex) { return false; }
    }
}
