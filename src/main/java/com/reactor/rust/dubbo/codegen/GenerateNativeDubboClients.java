package com.reactor.rust.dubbo.codegen;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Container annotation for multiple generated native Dubbo clients. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface GenerateNativeDubboClients {
    GenerateNativeDubboClient[] value();
}
