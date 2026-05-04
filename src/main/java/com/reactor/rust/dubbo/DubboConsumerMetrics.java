package com.reactor.rust.dubbo;

import java.util.concurrent.atomic.LongAdder;

public final class DubboConsumerMetrics {

    private final LongAdder referencesCreated = new LongAdder();
    private final LongAdder referenceCreateFailures = new LongAdder();
    private final LongAdder fieldsInjected = new LongAdder();
    private final LongAdder closeCalls = new LongAdder();

    void recordReferenceCreated() {
        referencesCreated.increment();
    }

    void recordReferenceCreateFailure() {
        referenceCreateFailures.increment();
    }

    void recordFieldInjected() {
        fieldsInjected.increment();
    }

    void recordClose() {
        closeCalls.increment();
    }

    public long referencesCreated() {
        return referencesCreated.sum();
    }

    public long referenceCreateFailures() {
        return referenceCreateFailures.sum();
    }

    public long fieldsInjected() {
        return fieldsInjected.sum();
    }

    public long closeCalls() {
        return closeCalls.sum();
    }

    public Snapshot snapshot() {
        return new Snapshot(referencesCreated(), referenceCreateFailures(), fieldsInjected(), closeCalls());
    }

    public record Snapshot(
            long referencesCreated,
            long referenceCreateFailures,
            long fieldsInjected,
            long closeCalls) {}
}
