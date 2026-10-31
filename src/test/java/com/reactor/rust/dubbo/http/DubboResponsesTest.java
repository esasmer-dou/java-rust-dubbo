package com.reactor.rust.dubbo.http;

import com.reactor.rust.dubbo.NativeResponseHandle;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.http.RawResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DubboResponsesTest {
    @Test void adapterFailurePreservesItsOriginalCauseForAnAlreadyAdaptedEntity() {
        var failure = new IllegalArgumentException("adapter failed");
        var response = ResponseEntity.ok(RawResponse.json(new byte[0]));
        var result = DubboResponses.replyAsync(CompletableFuture.completedFuture(response), value -> {
            throw failure;
        });
        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
    }

    @Test void cancelledHttpResultStillObservesLateRpcCompletion() {
        var source = new CompletableFuture<String>();
        var mapped = new AtomicInteger();
        var result = DubboResponses.replyAsync(source, value -> {
            mapped.incrementAndGet();
            return ResponseEntity.ok(RawResponse.json(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        });
        assertTrue(result.cancel(false));
        assertFalse(source.isCancelled());
        source.complete("{}");
        assertEquals(1, mapped.get());
        assertTrue(result.isCancelled());
    }

    @Test void asyncAdaptationPreservesErrorsAndExplicitStatus() {
        var error = new IllegalStateException("rpc failed");
        var failed = DubboResponses.replyAsync(CompletableFuture.failedFuture(error),
                (NativeResponseHandle handle) -> DubboResponses.reply(handle));
        assertSame(error, assertThrows(java.util.concurrent.CompletionException.class, failed::join).getCause());
        var result = DubboResponses.replyAsync(CompletableFuture.completedFuture(NativeResponseHandle.json(41)),
                handle -> DubboResponses.created(handle));
        assertEquals(201, result.join().getStatus().getCode());
    }

    @Test void transfersOnlyTheIdentifierAndKeepsStatusAndHeaders() {
        var handle = NativeResponseHandle.json(41);
        var response = DubboResponses.reply(ResponseEntity.created(handle).header("Location", "/customers/41"));
        assertEquals(201, response.getStatus().getCode());
        assertEquals("/customers/41", response.readOnlyHeaders().get("Location"));
        assertEquals(41, response.getBody().getNativeId());
        assertEquals(0, response.getBody().getBody().length);
        assertEquals(0, response.getBody().getEncodedHeaders().length);
        assertEquals(200, DubboResponses.reply(handle).getStatus().getCode());
        assertEquals(201, DubboResponses.created(handle).getStatus().getCode());
        assertEquals(200, DubboResponses.ok(handle).getStatus().getCode());
    }

    @Test void rejectsMissingHandlesAndPreservesAnEmptyEntity() {
        assertThrows(NullPointerException.class, () -> DubboResponses.body(null));
        assertNull(DubboResponses.reply(ResponseEntity.<NativeResponseHandle>noContent()).getBody());
    }
}
