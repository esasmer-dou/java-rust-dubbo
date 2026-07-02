package com.reactor.rust.dubbo;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptionalDependencyBoundaryTest {

    @Test
    void staticNativeConsumerClassesLoadWithoutHessianOrZookeeperDependencies() throws Exception {
        URL classes = Path.of("target", "classes").toAbsolutePath().toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("com.alibaba.com.caucho.hessian.io.Hessian2Input", false, loader));
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("org.apache.zookeeper.ZooKeeper", false, loader));

            assertDoesNotThrow(() ->
                    Class.forName("com.reactor.rust.dubbo.NativeDubboConsumerClient", false, loader));
            assertDoesNotThrow(() ->
                    Class.forName("com.reactor.rust.dubbo.internal.nativeclient.StaticNativeDubboReference", false, loader));
            assertDoesNotThrow(() ->
                    Class.forName("com.reactor.rust.dubbo.NativeDubboMethodInvoker", false, loader));
            assertDoesNotThrow(() ->
                    Class.forName("com.reactor.rust.dubbo.PendingNativeDubboInvocations", false, loader));
        }
    }
}
