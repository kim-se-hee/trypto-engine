package ksh.tryptoengine.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class EngineMetrics {

    private final MeterRegistry registry;
    private final Timer walAppend;
    private final Timer dbWrite;
    private final Counter matches;

    public EngineMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.walAppend = Timer.builder("engine.wal.append")
            .description("WAL batch processing latency (writes + fsync per batch)")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry);
        this.dbWrite = Timer.builder("engine.db.write")
            .description("Fill batch DB write latency (per batch)")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(registry);
        this.matches = Counter.builder("engine.match.count")
            .description("Filled order count; use rate() for throughput")
            .register(registry);
    }

    public MeterRegistry registry() {
        return registry;
    }

    public Timer walAppend() {
        return walAppend;
    }

    public Timer dbWrite() {
        return dbWrite;
    }

    public Counter matches() {
        return matches;
    }
}
