package com.reactor.rust.dubbo;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface DubboReference {

    String group() default "";

    String version() default "";

    String protocol() default "";

    String serialization() default "";

    String cluster() default "";

    String loadbalance() default "";

    int timeoutMs() default -1;

    int retries() default -1;

    int connections() default -1;

    boolean check() default false;

    boolean lazy() default false;
}
