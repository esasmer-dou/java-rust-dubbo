package com.reactor.rust.dubbo.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeDubboClientProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesDirectAsyncInvokersWithoutRuntimeProxy() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("src/generated/fixture"));
        Path generatedDir = Files.createDirectories(tempDir.resolve("generated"));
        Path classesDir = Files.createDirectories(tempDir.resolve("classes"));
        Path source = sourceDir.resolve("ClientDefinition.java");
        Files.writeString(source, """
                package generated.fixture;

                import com.reactor.rust.dubbo.codegen.GenerateNativeDubboClient;
                import java.util.List;

                @GenerateNativeDubboClient(
                        service = ClientDefinition.Service.class,
                        generatedName = "GeneratedClient",
                        retryReads = true,
                        retryProperty = "sample.retry",
                        exposeMetrics = true,
                        version = "0.0.0")
                public final class ClientDefinition {
                    public interface BaseService {
                        String inheritedName(long id);
                    }

                    public interface Service extends BaseService {
                        byte[] load(long id);
                        List<String> names(int limit);
                        boolean exists(long id);
                    }

                    @GenerateNativeDubboClient(
                            service = Service.class,
                            generatedName = "NoRetryClient")
                    static final class NoRetryDefinition {}
                }
                """, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    files,
                    null,
                    List.of(
                            "--release", "21",
                            "-proc:only",
                            "-classpath", System.getProperty("java.class.path"),
                            "-d", classesDir.toString(),
                            "-s", generatedDir.toString()),
                    null,
                    files.getJavaFileObjects(source.toFile()));
            task.setProcessors(List.of(new NativeDubboClientProcessor()));
            assertTrue(task.call());
        }

        String generated = Files.readString(
                generatedDir.resolve("generated/fixture/GeneratedClient.java"),
                StandardCharsets.UTF_8);
        assertTrue(generated.contains("NativeDubboMethodInvoker<byte[]> load"));
        assertTrue(generated.contains("NativeDubboMethodInvoker<java.util.List<java.lang.String>> names"));
        assertTrue(generated.contains("NativeDubboMethodInvoker<java.lang.Boolean> exists"));
        assertTrue(generated.contains("NativeDubboMethodInvoker<java.lang.String> inheritedName"));
        assertTrue(generated.contains("inheritedNameAsync(long id)"));
        assertTrue(generated.contains("client.method(spec, \"exists\", java.lang.Boolean.class, long.class)"));
        assertTrue(generated.contains(
                "support.referenceBuilder(generated.fixture.ClientDefinition.Service.class)"
                        + ".version(\"0.0.0\").build()"));
        assertTrue(generated.contains("loadNativeJsonAsync(long id)"));
        assertTrue(generated.contains("if (!retryReads)"));
        assertTrue(generated.contains("import com.reactor.rust.dubbo.policy.DubboReadRetry;"));
        assertTrue(generated.contains("return client.nativeMetricsJson()"));

        String noRetry = Files.readString(
                generatedDir.resolve("generated/fixture/NoRetryClient.java"),
                StandardCharsets.UTF_8);
        assertFalse(noRetry.contains("DubboReadRetry"));
    }

    @Test
    void rejectsGenericServiceContractsAtBuildTime() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("generic-src/generated/fixture"));
        Path generatedDir = Files.createDirectories(tempDir.resolve("generic-generated"));
        Path classesDir = Files.createDirectories(tempDir.resolve("generic-classes"));
        Path source = sourceDir.resolve("GenericClientDefinition.java");
        Files.writeString(source, """
                package generated.fixture;

                import com.reactor.rust.dubbo.codegen.GenerateNativeDubboClient;

                @GenerateNativeDubboClient(service = GenericClientDefinition.Service.class)
                public final class GenericClientDefinition {
                    public interface Service<T> {
                        T load(long id);
                    }
                }
                """, StandardCharsets.UTF_8);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    files,
                    diagnostics,
                    List.of(
                            "--release", "21",
                            "-proc:only",
                            "-classpath", System.getProperty("java.class.path"),
                            "-d", classesDir.toString(),
                            "-s", generatedDir.toString()),
                    null,
                    files.getJavaFileObjects(source.toFile()));
            task.setProcessors(List.of(new NativeDubboClientProcessor()));
            assertFalse(task.call());
        }

        assertTrue(diagnostics.getDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.getMessage(null)
                        .contains("generic service interfaces are not supported")));
    }

    @Test
    void generatesMultipleClientsFromOneDeclarativeDefinition() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("repeatable-src/generated/fixture"));
        Path generatedDir = Files.createDirectories(tempDir.resolve("repeatable-generated"));
        Path classesDir = Files.createDirectories(tempDir.resolve("repeatable-classes"));
        Path source = sourceDir.resolve("Clients.java");
        Files.writeString(source, """
                package generated.fixture;

                import com.reactor.rust.dubbo.codegen.EnableNativeDubboClients;
                import com.reactor.rust.dubbo.codegen.GenerateNativeDubboClient;

                @EnableNativeDubboClients(discoveryProperty = "sample.discovery")
                @GenerateNativeDubboClient(service = Clients.CatalogService.class)
                @GenerateNativeDubboClient(service = Clients.CustomerService.class)
                public final class Clients {
                    public interface CatalogService { byte[] catalog(); }
                    public interface CustomerService { boolean exists(long id); }
                }
                """, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    files,
                    null,
                    List.of(
                            "--release", "21",
                            "-proc:only",
                            "-classpath", System.getProperty("java.class.path"),
                            "-d", classesDir.toString(),
                            "-s", generatedDir.toString()),
                    null,
                    files.getJavaFileObjects(source.toFile()));
            task.setProcessors(List.of(new NativeDubboClientProcessor()));
            assertTrue(task.call());
        }

        assertTrue(Files.exists(generatedDir.resolve("generated/fixture/CatalogServiceClient.java")));
        assertTrue(Files.exists(generatedDir.resolve("generated/fixture/CustomerServiceClient.java")));
        String configuration = Files.readString(
                generatedDir.resolve("generated/fixture/Clients__DubboConfiguration.java"));
        assertTrue(configuration.contains(".discoveryProperty(\"sample.discovery\")"));
        assertTrue(configuration.contains("CatalogServiceClient.create(client, support)"));
        assertTrue(configuration.contains("CustomerServiceClient.create(client, support)"));
    }

    @Test
    void rejectsLifecycleWithoutAnyClientDeclaration() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("empty-src/generated/fixture"));
        Path generatedDir = Files.createDirectories(tempDir.resolve("empty-generated"));
        Path classesDir = Files.createDirectories(tempDir.resolve("empty-classes"));
        Path source = sourceDir.resolve("Clients.java");
        Files.writeString(source, """
                package generated.fixture;
                import com.reactor.rust.dubbo.codegen.EnableNativeDubboClients;
                @EnableNativeDubboClients
                public final class Clients {}
                """, StandardCharsets.UTF_8);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    files,
                    diagnostics,
                    List.of(
                            "--release", "21",
                            "-proc:only",
                            "-classpath", System.getProperty("java.class.path"),
                            "-d", classesDir.toString(),
                            "-s", generatedDir.toString()),
                    null,
                    files.getJavaFileObjects(source.toFile()));
            task.setProcessors(List.of(new NativeDubboClientProcessor()));
            assertFalse(task.call());
        }

        assertTrue(diagnostics.getDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.getMessage(null)
                        .contains("requires at least one @GenerateNativeDubboClient")));
    }
}
