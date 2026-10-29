package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.runtime.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only independent Dubbo framing; no second production transport. */
final class NativeWire {
    private static final AtomicLong IDS = new AtomicLong();
    record Reply(int status, int flag, Object value) {}
    record Envelope(long id, Reply reply) {}
    private record Call(NativeServiceContract<?> contract, NativeServiceContract.Method method) {}
    static Reply call(int port, NativeServiceContract<?> contract, String name, Object... arguments) throws IOException {
        return call(port, contract, name, null, arguments);
    }
    static Reply call(int port, NativeServiceContract<?> contract, String name, byte[] rawArguments, Object[] arguments) throws IOException {
        try (var pipe = new Pipeline(port)) {
            long id = pipe.sendRaw(contract,name,rawArguments,arguments);
            var result = pipe.receive(); check(result.id()==id,"response identity"); return result.reply();
        }
    }
    static final class Pipeline implements AutoCloseable {
        private final Socket socket = new Socket();
        private final java.util.Map<Long,Call> pending = new java.util.LinkedHashMap<>();
        Pipeline(int port) throws IOException {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000); socket.setSoTimeout(6000);
        }
        long send(NativeServiceContract<?> contract,String name,Object... arguments) throws IOException { return sendRaw(contract,name,null,arguments); }
        private long sendRaw(NativeServiceContract<?> contract,String name,byte[] rawArguments,Object[] arguments) throws IOException {
        check(pending.size()<8,"test pipeline capacity");
        var method = contract.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        var out = new Hessian2Output();
        try {
            out.attach(ByteBuffer.allocateDirect(4096), 0);
            out.writeString("2.0.2"); out.writeString(contract.serviceType().getName());
            out.writeString(contract.version()); out.writeString(name);
            out.writeString(method.descriptor().substring(1, method.descriptor().indexOf(')')));
            if (rawArguments == null) contract.writeArguments(method.id(), out, arguments);
            else for (byte b : rawArguments) out.buffer().put(b);
            out.writeMapStart(); out.writeMapEnd();
            byte[] body = new byte[out.position()];
            out.buffer().duplicate().flip().get(body);
            long id = IDS.incrementAndGet();
                var wire = new DataOutputStream(socket.getOutputStream());
                wire.writeShort(0xdabb); wire.writeByte(0xc2); wire.writeByte(0); wire.writeLong(id); wire.writeInt(body.length);
                wire.write(body); wire.flush();
                pending.put(id,new Call(contract,method)); return id;
        } finally { out.detach(); }
        }
        Envelope receive() throws IOException {
                var input = new DataInputStream(socket.getInputStream());
                check(input.readUnsignedShort() == 0xdabb, "response magic");
                int flags = input.readUnsignedByte(), status = input.readUnsignedByte();
                check((flags & 0x80) == 0 && (flags & 0x1f) == 2, "response flags");
                long id = input.readLong(); var call = pending.remove(id); check(call!=null,"response identity");
                int length = input.readInt(); check(length >= 0 && length <= 1024 * 1024, "response bound");
                byte[] response = input.readNBytes(length); check(response.length == length, "response complete");
                var in = new Hessian2Input();
                try {
                    ByteBuffer buffer = ByteBuffer.allocateDirect(length); buffer.put(response);
                    in.attach(buffer, length, 1024);
                    if (status != 20) return new Envelope(id,new Reply(status, -1, in.readString()));
                    int flag = in.readInt();
                    Object result = switch (flag) {
                        case 0 -> in.readDynamic();
                        case 1 -> call.contract().readResult(call.method().id(), in);
                        case 2 -> null;
                        default -> throw new AssertionError("Unexpected response flag " + flag);
                    };
                    check(in.remaining() == 0, "response trailing bytes");
                    return new Envelope(id,new Reply(status, flag, result));
                } finally { in.detach(); }
        }
        @Override public void close() throws IOException { pending.clear(); socket.close(); }
    }
    static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
