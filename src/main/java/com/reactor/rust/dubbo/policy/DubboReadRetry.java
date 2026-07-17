package com.reactor.rust.dubbo.policy;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Bounded read retry policy for connection-abort failures only.
 */
public final class DubboReadRetry {

    private DubboReadRetry() {}

    public static <T> CompletableFuture<T> onceOnConnectionAbort(
            boolean enabled,
            Supplier<CompletableFuture<T>> call) {
        CompletableFuture<T> first;
        try {
            first = call.get();
        } catch (Throwable error) {
            return enabled && isConnectionAbort(error)
                    ? invokeAgain(call)
                    : CompletableFuture.failedFuture(error);
        }
        if (!enabled) {
            return first;
        }
        return first.exceptionallyCompose(error -> isConnectionAbort(error)
                ? invokeAgain(call)
                : CompletableFuture.failedFuture(error));
    }

    public static boolean isConnectionAbort(Throwable error) {
        Throwable current = unwrap(error);
        while (current != null) {
            String message = current.getMessage();
            if (message != null && isConnectionAbortMessage(message)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static <T> CompletableFuture<T> invokeAgain(Supplier<CompletableFuture<T>> call) {
        try {
            return call.get();
        } catch (Throwable retryError) {
            return CompletableFuture.failedFuture(retryError);
        }
    }

    private static Throwable unwrap(Throwable error) {
        if ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private static boolean isConnectionAbortMessage(String message) {
        String value = message.toLowerCase(Locale.ROOT);
        if (!value.contains("i/o error")) {
            return false;
        }
        return value.contains("aborted")
                || value.contains("connection reset")
                || value.contains("forcibly closed")
                || value.contains("broken pipe")
                || value.contains("os error 10053")
                || value.contains("os error 10054");
    }
}
