package com.reactor.rust.dubbo.provider;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Generate a static native-provider binding for an existing, unmodified service interface. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface GenerateNativeProvider {
    Class<?> service();
    String group() default "";
    String version() default "0.0.0";
}
