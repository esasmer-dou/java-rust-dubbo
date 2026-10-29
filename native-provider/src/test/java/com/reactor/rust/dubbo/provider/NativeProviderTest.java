package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.runtime.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeProviderTest {
    private static final NativeServiceContract<NativeFixtures.ProbeService> CONTRACT = ProbeDefinition__NativeProvider.CONTRACT;

    @Test void nestedRecordsCollectionsEnumsNullableAndConstructorRoundTrip() {
        var row = new NativeFixtures.Row("  \u0130pek \ud83d\ude80  ", null, NativeFixtures.State.ACTIVE);
        var page = new NativeFixtures.Page(List.of(row), Map.of("\u015f", 7), (byte)-128, '\uffff', 1.234);
        assertEquals(page, roundTrip("echo", page));
        assertEquals("\u0130pek \ud83d\ude80", row.text());
    }
    @Test void primitiveDouble0x5fDecodesAsScaledInteger() {
        var in = new Hessian2Input(); ByteBuffer b = ByteBuffer.allocateDirect(5).put((byte)0x5f).putInt(-1234);
        try { in.attach(b, 5, 32); assertEquals(-1.234d, CONTRACT.readArguments(method("fraction").id(), in)[0]); }
        finally { in.detach(); }
    }
    @Test void allPrimitiveAndBoxedScalarsPreserveExtremesAndNegativeZero() {
        var values = new NativeFixtures.Scalars(true,Byte.MIN_VALUE,Short.MIN_VALUE,'\uffff',Integer.MIN_VALUE,Long.MIN_VALUE,-0.0f,-0.0d,
                false,Byte.MAX_VALUE,Short.MAX_VALUE,'\u0000',Integer.MAX_VALUE,Long.MAX_VALUE,Float.POSITIVE_INFINITY,Double.NaN);
        assertEquals(values, roundTrip("scalars",values));
        var nullable = new NativeFixtures.Scalars(false,(byte)0,(short)0,'\u0000',0,0,0,0,null,null,null,null,null,null,null,null);
        assertEquals(nullable, roundTrip("scalars",nullable));
    }
    @Test void chunkBoundaryInsideSurrogatePairAndLongUtf16String() {
        String text = "a".repeat(65534) + "\ud83d\ude80\u0130";
        var page = new NativeFixtures.Page(List.of(new NativeFixtures.Row(text,1L,NativeFixtures.State.NEW)),Map.of(),(byte)1,'x',0);
        assertEquals(page, roundTrip("echo",page));
    }
    @Test void nullAndVoidResults() {
        assertNull(roundTrip("echo", null));
        assertNull(CONTRACT.readResult(method("notify").id(), new Hessian2Input()));
    }
    @Test void malformedSchemaAndUnresolvedReferenceFailClosed() {
        var out = new Hessian2Output(); var in = new Hessian2Input();
        try {
            out.attach(ByteBuffer.allocateDirect(4096), 0);
            out.writeObjectStart(NativeFixtures.Page.class.getName(), new String[]{"unknown"}); out.writeNull();
            in.attach(out.buffer(), out.position(), 32);
            assertThrows(DubboCodecException.class, () -> CONTRACT.readArguments(method("echo").id(), in));
            in.detach(); out.rewind(0); out.buffer().put((byte)'Q'); out.writeInt(0);
            in.attach(out.buffer(), out.position(), 32);
            assertThrows(DubboCodecException.class, () -> CONTRACT.readArguments(method("echo").id(), in));
        } finally { in.detach(); out.detach(); }
    }
    @Test void recordCanonicalConstructorIsActuallyInvokedOnDecode() {
        var out = new Hessian2Output(); var in = new Hessian2Input();
        try {
            out.attach(ByteBuffer.allocateDirect(4096), 0);
            out.writeObjectStart(NativeFixtures.Page.class.getName(), new String[]{"rows","counts","flag","letter","amount"});
            out.writeListStart(1);
            out.writeObjectStart(NativeFixtures.Row.class.getName(), new String[]{"text","id","state"});
            out.writeString("  normalized  "); out.writeNull(); out.writeNull();
            out.writeMapStart(); out.writeMapEnd(); out.writeInt(0); out.writeInt(0); out.writeDouble(0);
            in.attach(out.buffer(), out.position(), 32);
            var value = (NativeFixtures.Page) CONTRACT.readArguments(method("echo").id(), in)[0];
            assertEquals("normalized", value.rows().getFirst().text());
        } finally { in.detach(); out.detach(); }
    }
    @Test void collectionBoundBeforeAllocationAndScalarRanges() {
        var out = new Hessian2Output(); var in = new Hessian2Input();
        try {
            out.attach(ByteBuffer.allocateDirect(4096), 0);
            out.writeObjectStart(NativeFixtures.Page.class.getName(), new String[]{"rows","counts","flag","letter","amount"});
            out.writeListStart(Integer.MAX_VALUE);
            in.attach(out.buffer(), out.position(), 32);
            assertThrows(DubboCodecException.class, () -> CONTRACT.readArguments(method("echo").id(), in));
            assertThrows(DubboCodecException.class, () -> NativeProviderCodec.checkedInt(128, -128, 127));
            assertThrows(DubboCodecException.class, () -> NativeProviderCodec.collectionSize(-1, 32));
        } finally { in.detach(); out.detach(); }
    }
    @Test void serviceCapRejectsBeforeDecoratorAndExceptionReleasesPermit() throws Throwable {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger decorated = new AtomicInteger();
        NativeFixtures.ProbeService target = new NativeFixtures.ProbeService() {
            public NativeFixtures.Page echo(NativeFixtures.Page page) { return page; }
            public double fraction(double value) throws IllegalStateException {
                entered.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                throw new IllegalStateException("business");
            }
            public void notify(String message) {}
            public NativeFixtures.Scalars scalars(NativeFixtures.Scalars value) { return value; }
        };
        var binding = ProbeDefinition__NativeProvider.bind(target, PlainDubboProvider.ServiceExecutionConfig.bounded(1));
        var prepared = binding.prepare(new ProviderDispatcherDecorator() {
            public <T> ProviderMethodDispatcher<T> decorate(Class<T> t, ProviderMethodDispatcher<T> d) {
                return (service,name,types,args) -> { decorated.incrementAndGet(); return d.invoke(service,name,types,args); };
            }
        });
        try (ExecutorService threads = Executors.newSingleThreadExecutor()) {
            var first = threads.submit(() -> { assertThrows(IllegalStateException.class, () -> prepared.invoke(method("fraction"), new Object[]{1d})); });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () -> prepared.invoke(method("notify"), new Object[]{"x"}));
            assertEquals(1, decorated.get());
            release.countDown(); first.get(3, TimeUnit.SECONDS);
            assertNull(prepared.invoke(method("notify"), new Object[]{"x"})); assertEquals(2, decorated.get());
        } finally { release.countDown(); }
    }
    @Test void rejectsInvalidLimitsAndConfigurationBeforeNativeLoad() {
        assertThrows(IllegalArgumentException.class, () -> new NativeProviderFactory.Options(0, 1, 1, 1, 1024, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new NativeProviderFactory.Options(20000, 1, 64, 1024, 1024*1024, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> NativeProviderFactory.start(NativeProviderFactory.Options.defaults(20000), List.of(), null));
        var binding = CatalogDefinition__NativeProvider.bind(() -> new byte[0], PlainDubboProvider.ServiceExecutionConfig.bounded(1));
        assertThrows(IllegalArgumentException.class, () -> NativeProviderFactory.start(NativeProviderFactory.Options.defaults(20000), List.of(binding,binding), null));
        assertThrows(IllegalArgumentException.class, () -> CatalogDefinition__NativeProvider.bind(() -> new byte[0],
                PlainDubboProvider.ServiceExecutionConfig.bounded(1, Map.of("typo",1))));
    }
    @Test void generatedDispatcherIsTypedAndMetadataDefensivelyCopies() {
        var m = method("fraction"); var types = m.parameterTypes(); types[0] = String.class;
        assertEquals(double.class, m.parameterTypes()[0]);
        assertEquals("(D)D", m.descriptor());
        assertThrows(IllegalArgumentException.class, () -> CONTRACT.dispatcher().invoke(null, "notAllowed", new Class<?>[0], new Object[0]));
    }
    @Test void oldAbiThreeAndMissingOrUnboundedAdmissionCapabilitiesRejectedAtStartup() {
        assertThrows(IllegalStateException.class,()->NativeProviderFactory.validateNativeAdmission("{\"abi\":3}"));
        String good="{\"providerWireBytesLimit\":33554432,\"providerConnectionAdmissionLimit\":256,\"providerResponseAdmissionLimit\":256,\"providerReaderPolicyVersion\":1,\"providerReaderDepthLimit\":64,\"providerReaderWorkLimit\":65536,\"providerReaderMetadataBytesLimit\":65536}";
        assertThrows(IllegalStateException.class,()->NativeProviderFactory.validateNativeAdmission(good.replace(",\"providerReaderPolicyVersion\":1", "")));
        assertThrows(IllegalStateException.class,()->NativeProviderFactory.validateNativeAdmission(good.replace(",\"providerReaderDepthLimit\":64", "")));
        assertThrows(IllegalStateException.class,()->NativeProviderFactory.validateNativeAdmission(good.replace("\"providerReaderDepthLimit\":64", "\"providerReaderDepthLimit\":65")));
        assertDoesNotThrow(()->NativeProviderFactory.validateNativeAdmission(good));
        assertThrows(IllegalStateException.class,()->NativeProviderFactory.validateNativeAdmission(good.replace("33554432","335544320")));
        assertThrows(IllegalStateException.class,()->NativeProviderFactory.validateNativeAdmission(good.replace("\"providerConnectionAdmissionLimit\":256","\"providerConnectionAdmissionLimit\":0")));
        assertThrows(IllegalStateException.class,()->NativeProviderFactory.validateNativeAdmission(good.replace("33554432","9999999999999999999999999999999")));
        assertThrows(IllegalStateException.class,()->NativeProviderFactory.validateNativeAdmission(good.replace("33554432,","33554432,\"providerWireBytesLimit\":33554432,")));
    }
    private static NativeServiceContract.Method method(String name) { return CONTRACT.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow(); }
    private static Object roundTrip(String name, Object arg) {
        var out = new Hessian2Output(); var in = new Hessian2Input();
        try {
            out.attach(ByteBuffer.allocateDirect(4096), 0); CONTRACT.writeArguments(method(name).id(), out, new Object[]{arg});
            in.attach(out.buffer(), out.position(), 32); Object value = CONTRACT.readArguments(method(name).id(), in)[0];
            assertEquals(0, in.remaining()); return value;
        } finally { in.detach(); out.detach(); }
    }
}
