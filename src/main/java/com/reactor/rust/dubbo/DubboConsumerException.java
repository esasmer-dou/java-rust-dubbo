package com.reactor.rust.dubbo;

public final class DubboConsumerException extends RuntimeException {

    public DubboConsumerException(String message) {
        super(message);
    }

    public DubboConsumerException(String message, Throwable cause) {
        super(message, cause);
    }
}
