package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.nativeprovider.codegen.NativeProviderProcessor;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeProcessorTest {
    @TempDir Path temp;
    @Test void cyclicRecordsRejectedAtCompileTime() { rejects("public record Node(Node next) {} public interface S { Node read(); }", "Cyclic DTO"); }
    @Test void futureAndReflectiveBeansRejectedAtCompileTime() { rejects("public interface S { java.util.concurrent.CompletableFuture<String> read(); }", "Unsupported static codec type"); }
    @Test void overloadedMethodsRejectedAtCompileTime() { rejects("public interface S { int read(int x); int read(long x); }", "Overloaded"); }
    @Test void ambiguousMapKeyRejectedAtCompileTime() { rejects("public interface S { java.util.Map<Long,String> read(); }", "Only Map<String,V>"); }
    @Test void concreteStaticRecordsAcceptedWithoutRuntimeReflection() {
        var result = compile("public record Data(String name, Long id) {} public interface S { Data read(Data input); }");
        assertTrue(result.success, result.errors);
    }
    private void rejects(String declarations, String expected) {
        var result = compile(declarations); assertFalse(result.success); assertTrue(result.errors.contains(expected), result.errors);
    }
    private Result compile(String declarations) {
        String source = "package proof; import com.reactor.rust.dubbo.provider.GenerateNativeProvider; public class Definition { "
                + declarations + " } @GenerateNativeProvider(service=Definition.S.class) final class Bind {}";
        var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///proof/Definition.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, manager, diagnostics, List.of("--release", "21", "-classpath", System.getProperty("java.class.path"),
                    "-d", temp.toString(), "-s", temp.toString()), null, List.of(file));
            task.setProcessors(List.of(new NativeProviderProcessor()));
            boolean success = task.call(); return new Result(success, diagnostics.getDiagnostics().toString());
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    private record Result(boolean success, String errors) {}
}
