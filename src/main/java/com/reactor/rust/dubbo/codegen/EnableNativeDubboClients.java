package com.reactor.rust.dubbo.codegen;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Generates one bounded native client lifecycle and DI beans for declared Dubbo clients. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface EnableNativeDubboClients {

    String discoveryProperty() default "reactor.dubbo.discovery";

    String generatedConfigurationName() default "";

    /** Rejects ZooKeeper discovery and creates a static-provider-only transport. */
    boolean staticOnly() default false;
}
