package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.runtime.Hessian2Input;
import com.reactor.rust.dubbo.sample.CatalogJsonService;
import com.reactor.rust.dubbo.sample.dto.CreateCustomerCommand;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeIntegrationTest {
    @Test void realNativeRpcAndActualClassLoadDenylist() throws Exception {
        String dll = System.getProperty("trackA.native.path");
        assertNotNull(dll, "Pass explicit -DtrackA.native.path=<accepted DLL or SO>; this proof never silently skips");
        assertTrue(Files.isRegularFile(Path.of(dll)), "Native library must exist");
        String log = run(NativeRpcProbe.class, List.of(location(NativeRpcProbe.class), location(NativeProviderFactory.class), location(ProviderMethodDispatcher.class),
                location(Hessian2Input.class), location(CatalogJsonService.class), location(CreateCustomerCommand.class)),
                List.of("-Dreactor.dubbo.native.path=" + Path.of(dll).toAbsolutePath()), "native-classes.log");
        for (String marker : List.of("catalogAndAllCustomerMethodsRoundTrip", "chunkedGrowthScaledDoubleAndVoid", "malformedAndBusinessFailureRelease", "methodCapAndServiceIsolation",
                "drainAndIdempotentClose", "oneShotRegistryExplicit")) assertTrue(log.contains("PROOF_PASS " + marker), marker);
        assertTrue(log.contains("NATIVE_RPC_PASS"));
        String normalized = log.lines().filter(line -> line.startsWith("class load: ") || line.contains("[class,load]"))
                .map(line -> line.replace('/', '.')).collect(java.util.stream.Collectors.joining("\n"));
        for (String required : List.of("com.reactor.rust.dubbo.provider.NativeProviderFactory", "com.reactor.rust.dubbo.provider.CustomerDefinition__NativeProvider",
                "com.reactor.rust.dubbo.runtime.NativeDubboBridge", "com.reactor.rust.dubbo.runtime.Hessian2Input"))
            assertTrue(normalized.contains(required), "Actual loaded class missing: " + required);
        for (String denied : List.of("org.apache.dubbo.", "io.netty.", "org.springframework.", "MethodHandleProviderDispatcher"))
            assertFalse(normalized.contains(denied), "Forbidden loaded class: " + denied);
    }
    @Test void unusedOptionalAdapterAbsentFromDefaultClassPathAndLoads() throws Exception {
        String log = run(DefaultPathProbe.class, List.of(location(DefaultPathProbe.class), location(ProviderMethodDispatcher.class)), List.of(), "default-classes.log");
        assertTrue(log.contains("DEFAULT_PATH_PASS"));
        assertFalse(log.contains("NativeProviderFactory")); assertFalse(log.contains("com.reactor.rust.dubbo.runtime"));
    }
    @Test void closeDeadlineDoesNotRevokeLateCallbackMemory() throws Exception {
        String dll = System.getProperty("trackA.native.path"); assertNotNull(dll);
        String log = run(NativeDeadlineProbe.class, List.of(location(NativeDeadlineProbe.class), location(NativeProviderFactory.class),
                location(ProviderMethodDispatcher.class), location(Hessian2Input.class), location(CatalogJsonService.class), location(CreateCustomerCommand.class)),
                List.of("-Dreactor.dubbo.native.path=" + Path.of(dll).toAbsolutePath()), "deadline-classes.log");
        assertTrue(log.contains("DEADLINE_CLOSE_PASS calls=1"));
    }
    @Test void nativeQueuePayloadAndCasReentrantContextCaps() throws Exception {
        String dll = System.getProperty("trackA.native.path"); assertNotNull(dll);
        String log = run(NativeCapacityProbe.class, List.of(location(NativeCapacityProbe.class), location(NativeProviderFactory.class),
                location(ProviderMethodDispatcher.class), location(Hessian2Input.class), location(CatalogJsonService.class), location(CreateCustomerCommand.class)),
                List.of("-Dreactor.dubbo.native.path=" + Path.of(dll).toAbsolutePath()), "capacity-classes.log");
        assertTrue(log.contains("CAPACITY_PASS active=1 queued=1 rejected=1 connections=2 recovered=1 calls=3 nestedRejected=3"));
    }
    @Test void nativeFrameBudgetReservedBeforePartialBodyAllocationAndReclaimedOnDisconnect() throws Exception {
        String dll=System.getProperty("trackA.native.path");assertNotNull(dll);
        String log=run(NativeBudgetProbe.class,List.of(location(NativeBudgetProbe.class),location(NativeProviderFactory.class),
                location(ProviderMethodDispatcher.class),location(Hessian2Input.class),location(CatalogJsonService.class),location(CreateCustomerCommand.class)),
                List.of("-Dreactor.dubbo.native.path="+Path.of(dll).toAbsolutePath()),"byte-budget-classes.log");
        assertTrue(log.contains("BYTE_BUDGET_PASS limit=33554432 partialFrames=32 rejected=1 recoveredRpc=1"));
    }
    @Test void lateNativeBusinessCallbackDoesNotPinNaturalJvmExit() throws Exception {
        String dll=System.getProperty("trackA.native.path");assertNotNull(dll);
        String log=run(NativeExitProbe.class,List.of(location(NativeExitProbe.class),location(NativeProviderFactory.class),
                location(ProviderMethodDispatcher.class),location(Hessian2Input.class),location(CatalogJsonService.class),location(CreateCustomerCommand.class)),
                List.of("-Dreactor.dubbo.native.path="+Path.of(dll).toAbsolutePath()),"natural-exit-classes.log");
        assertTrue(log.contains("DAEMON_NATURAL_EXIT_PASS activeCallback=1"));
    }
    private static String run(Class<?> main, List<String> entries, List<String> options, String name) throws Exception {
        Path proof = Path.of(System.getProperty("trackA.proof.dir", "target/native-proof")).toAbsolutePath(); Files.createDirectories(proof);
        Path log = proof.resolve(name);
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xms32m", "-Xmx384m", "-XX:ActiveProcessorCount=2", "-verbose:class"));
        command.addAll(options); command.add("-cp"); command.add(String.join(File.pathSeparator, new LinkedHashSet<>(entries))); command.add(main.getName());
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(35, TimeUnit.SECONDS), "Child deadline: " + log);
            assertEquals(0, child.exitValue(), () -> "Native proof failed: " + log + "\n" + tail(log));
            return Files.readString(log);
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
    }
    private static String tail(Path file) { try { String s = Files.readString(file); return s.substring(Math.max(0,s.length()-10000)); } catch (Exception e) { return e.toString(); } }
    private static String location(Class<?> type) throws Exception { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
}
