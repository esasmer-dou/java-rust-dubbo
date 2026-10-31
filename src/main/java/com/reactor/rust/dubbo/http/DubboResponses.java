package com.reactor.rust.dubbo.http;

import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.dubbo.NativeResponseHandle;
import com.reactor.rust.http.HttpStatus;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.http.ResponseEntity;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Optional REST adapter. Native headers and body remain in Rust; only ownership crosses JNI. */
public final class DubboResponses {
    private DubboResponses() {}

    public static RawResponse body(NativeResponseHandle handle) {
        return RawResponse.ownedNativeResponse(Objects.requireNonNull(handle, "handle").nativeId());
    }

    public static ResponseEntity<RawResponse> ok(NativeResponseHandle handle) {
        return ResponseEntity.ok(body(handle));
    }

    public static ResponseEntity<RawResponse> created(NativeResponseHandle handle) {
        return ResponseEntity.created(body(handle));
    }

    /** Used by generated route adapters for a direct native result. */
    public static ResponseEntity<RawResponse> reply(NativeResponseHandle handle) {
        Objects.requireNonNull(handle, "handle");
        try {
            return new ResponseEntity<>(body(handle), HttpStatus.valueOf(handle.statusCode()));
        } catch (RuntimeException | Error failure) {
            NativeBridge.discardNativeResponse(handle.nativeId());
            throw failure;
        }
    }

    /** Preserves an application's explicit HTTP status and headers. */
    public static ResponseEntity<RawResponse> reply(ResponseEntity<NativeResponseHandle> response) {
        Objects.requireNonNull(response, "response");
        NativeResponseHandle handle = response.getBody();
        try {
            return new ResponseEntity<>(handle == null ? null : body(handle), response.getStatus(), response.readOnlyHeaders());
        } catch (RuntimeException | Error failure) {
            if (handle != null) NativeBridge.discardNativeResponse(handle.nativeId());
            throw failure;
        }
    }

    /** Observes late results after HTTP cancellation so their native ownership is reclaimed. */
    public static <T> CompletableFuture<ResponseEntity<RawResponse>> replyAsync(
            CompletionStage<T> source, Function<? super T, ResponseEntity<RawResponse>> adapter) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(adapter, "adapter");
        var target = new CompletableFuture<ResponseEntity<RawResponse>>();
        // Do not cancel the source: its bounded RPC deadline must still deliver a releasable result.
        source.whenComplete((value, error) -> {
            if (error != null) {
                target.completeExceptionally(error);
                return;
            }
            ResponseEntity<RawResponse> response;
            try {
                response = adapter.apply(value);
            } catch (Throwable failure) {
                try {
                    discard(value);
                } catch (Throwable cleanupFailure) {
                    if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
                }
                target.completeExceptionally(failure);
                return;
            }
            if (!target.complete(response) && response != null && response.getBody() != null) {
                response.getBody().close();
            }
        });
        return target;
    }

    private static void discard(Object value) {
        if (value instanceof NativeResponseHandle handle) {
            // Native discard atomically removes transient ownership; an adapter may already have rejected it.
            NativeBridge.discardNativeResponse(handle.nativeId());
        } else if (value instanceof ResponseEntity<?> response) {
            discard(response.getBody());
        } else if (value instanceof RawResponse body) {
            body.close();
        }
    }
}
