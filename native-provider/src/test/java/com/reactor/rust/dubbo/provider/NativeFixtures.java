package com.reactor.rust.dubbo.provider;

import com.reactor.rust.dubbo.sample.CatalogJsonService;
import com.reactor.rust.dubbo.sample.CustomerCommandService;
import java.util.List;
import java.util.Map;

@GenerateNativeProvider(service = CatalogJsonService.class)
final class CatalogDefinition {}

@GenerateNativeProvider(service = CustomerCommandService.class)
final class CustomerDefinition {}

@GenerateNativeProvider(service = NativeFixtures.ProbeService.class)
final class ProbeDefinition {}

public final class NativeFixtures {
    public enum State { NEW, ACTIVE }
    public record Row(String text, Long id, State state) {
        public Row { if (text != null) text = text.strip(); }
    }
    public record Page(List<Row> rows, Map<String, Integer> counts, byte flag, char letter, double amount) {}
    public record Scalars(boolean z, byte b, short s, char c, int i, long j, float f, double d,
                          Boolean zz, Byte bb, Short ss, Character cc, Integer ii, Long jj, Float ff, Double dd) {}
    public interface ProbeService {
        Page echo(Page page);
        double fraction(double value);
        Scalars scalars(Scalars value);
        void notify(String message);
    }
}
