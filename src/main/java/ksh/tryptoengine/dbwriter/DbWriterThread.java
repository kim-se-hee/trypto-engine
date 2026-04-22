package ksh.tryptoengine.dbwriter;

import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import ksh.tryptoengine.event.FillCommand;
import ksh.tryptoengine.metrics.EngineMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Component
@RequiredArgsConstructor
public class DbWriterThread {

    private static final int MAX_BATCH = 256;

    private final FillTransactionExecutor executor;
    private final EngineMetrics metrics;
    private final BlockingQueue<FillCommand> channel = new LinkedBlockingQueue<>(16384);

    private Thread thread;
    private volatile boolean running = true;

    public void start() {
        thread = new Thread(this::loop, "engine-dbwriter");
        thread.setDaemon(false);
        thread.start();
    }

    public void offer(FillCommand cmd) {
        try {
            channel.put(cmd);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("db writer offer interrupted", e);
        }
    }

    private void loop() {
        List<FillCommand> batch = new ArrayList<>(MAX_BATCH);
        while (running) {
            try {
                batch.add(channel.take());
                channel.drainTo(batch, MAX_BATCH - 1);
                Timer.Sample sample = Timer.start(metrics.registry());
                try {
                    executor.executeBatch(batch);
                } finally {
                    sample.stop(metrics.dbWrite());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("db writer processing failed size={}", batch.size(), e);
            } finally {
                batch.clear();
            }
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }
}
