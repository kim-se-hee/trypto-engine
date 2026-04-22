package ksh.tryptoengine.wal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import ksh.tryptoengine.event.EngineInboundEvent;
import ksh.tryptoengine.event.OrderCanceledEvent;
import ksh.tryptoengine.event.OrderPlacedEvent;
import ksh.tryptoengine.event.TickReceivedEvent;
import ksh.tryptoengine.metrics.EngineMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Component
public class WalWriter {

    static final String WAL_FILE = "wal.log";
    static final String WAL_OLD_FILE = "wal.log.old";
    private static final int BATCH_MAX = 100;

    private final BlockingQueue<WalCommand> channel = new LinkedBlockingQueue<>(16384);
    private long sequence = 0;
    private final ObjectMapper mapper;
    private final Path walDir;
    private final EngineMetrics metrics;

    private Thread thread;
    private volatile boolean running = true;
    private FileOutputStream out;
    private BufferedWriter writer;

    public WalWriter(ObjectMapper mapper, EngineMetrics metrics, @Value("${engine.wal.dir}") String walDir) {
        this.mapper = mapper;
        this.metrics = metrics;
        this.walDir = Path.of(walDir);
    }

    public Path walDir() {
        return walDir;
    }

    public void setSequence(long seq) {
        this.sequence = seq;
    }

    public long currentSequence() {
        return sequence;
    }

    public void start() throws IOException {
        Files.createDirectories(walDir);
        openWriter();
        thread = new Thread(this::loop, "engine-wal");
        thread.setDaemon(false);
        thread.start();
        log.info("WAL writer started dir={} startSeq={}", walDir.toAbsolutePath(), sequence);
    }

    public long offer(EngineInboundEvent event) {
        long seq = ++sequence;
        JsonNode node = mapper.valueToTree(event);
        WalRecord rec = new WalRecord(seq, typeOf(event), node);
        try {
            channel.put(new WalCommand.Write(rec));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("WAL offer interrupted", e);
        }
        return seq;
    }

    public void rotate() {
        CountDownLatch done = new CountDownLatch(1);
        try {
            channel.put(new WalCommand.Rotate(done));
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("WAL rotate interrupted", e);
        }
    }

    public void flush() {
        CountDownLatch done = new CountDownLatch(1);
        try {
            channel.put(new WalCommand.Flush(done));
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("WAL flush interrupted", e);
        }
    }

    private void loop() {
        List<WalCommand> batch = new ArrayList<>(BATCH_MAX);
        while (running) {
            try {
                batch.add(channel.take());
                channel.drainTo(batch, BATCH_MAX - 1);
                processBatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                batch.clear();
            }
        }
    }

    private void processBatch(List<WalCommand> batch) {
        int pendingWrites = 0;
        Timer.Sample sample = null;

        for (WalCommand cmd : batch) {
            try {
                switch (cmd) {
                    case WalCommand.Write w -> {
                        if (sample == null) sample = Timer.start(metrics.registry());
                        writeLineNoSync(w.record());
                        pendingWrites++;
                    }
                    case WalCommand.Rotate r -> {
                        if (pendingWrites > 0) {
                            syncAll();
                            sample.stop(metrics.walAppend());
                            pendingWrites = 0;
                            sample = null;
                        }
                        try {
                            rotateFile();
                        } finally {
                            r.done().countDown();
                        }
                    }
                    case WalCommand.Flush f -> {
                        if (pendingWrites > 0) {
                            syncAll();
                            sample.stop(metrics.walAppend());
                            pendingWrites = 0;
                            sample = null;
                        }
                        f.done().countDown();
                    }
                }
            } catch (IOException e) {
                log.error("WAL operation failed", e);
            }
        }

        if (pendingWrites > 0) {
            try {
                syncAll();
                sample.stop(metrics.walAppend());
            } catch (IOException e) {
                log.error("WAL fsync failed", e);
            }
        }
    }

    private void openWriter() throws IOException {
        Path file = walDir.resolve(WAL_FILE);
        out = new FileOutputStream(file.toFile(), true);
        writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    private void writeLineNoSync(WalRecord rec) throws IOException {
        String line = mapper.writeValueAsString(rec);
        writer.write(line);
        writer.newLine();
    }

    private void syncAll() throws IOException {
        writer.flush();
        out.getFD().sync();
    }

    private void rotateFile() throws IOException {
        writer.flush();
        out.getFD().sync();
        writer.close();
        Path walFile = walDir.resolve(WAL_FILE);
        Path oldFile = walDir.resolve(WAL_OLD_FILE);
        Files.move(walFile, oldFile, StandardCopyOption.REPLACE_EXISTING);
        openWriter();
        Files.deleteIfExists(oldFile);
    }

    private String typeOf(EngineInboundEvent event) {
        return switch (event) {
            case OrderPlacedEvent ignored -> "OrderPlaced";
            case OrderCanceledEvent ignored -> "OrderCanceled";
            case TickReceivedEvent ignored -> "TickReceived";
        };
    }

    @PreDestroy
    public void stop() {
        if (!running) return;
        try {
            flush();
        } catch (Exception ignore) { }
        running = false;
        if (thread != null) thread.interrupt();
        try {
            if (thread != null) thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (writer != null) writer.close();
        } catch (IOException ignore) { }
    }
}
