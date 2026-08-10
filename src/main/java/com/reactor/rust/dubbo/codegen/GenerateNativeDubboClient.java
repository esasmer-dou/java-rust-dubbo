package com.reactor.rust.dubbo.codegen;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Target;

/**
 * Build-time definition for an allocation-conscious native Dubbo client wrapper.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Repeatable(GenerateNativeDubboClients.class)
public @interface GenerateNativeDubboClient {

    Class<?> service();

    String generatedName() default "";

    boolean retryReads() default false;

    String retryProperty() default "reactor.dubbo.read-retry-on-io-error";

    boolean exposeMetrics() default false;

    String group() default "";

    String version() default "";

    /** Startup property that controls whether this generated client bean is registered. */
    String enabledProperty() default "";

    /** Required value for {@link #enabledProperty()}; empty means any non-empty value. */
    String havingValue() default "true";

    /** Keeps existing declarations enabled when the controlling property is absent. */
    boolean matchIfMissing() default false;
}
