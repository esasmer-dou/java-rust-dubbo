package com.reactor.rust.dubbo;

final class CloseFailures {

    private CloseFailures() {
    }

    static Throwable add(Throwable current, Throwable next) {
        if (current == null) {
            return next;
        }
        if (current != next) {
            current.addSuppressed(next);
        }
        return current;
    }

    static void rethrow(Throwable failure, String message) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new DubboConsumerException(message, failure);
    }
}
