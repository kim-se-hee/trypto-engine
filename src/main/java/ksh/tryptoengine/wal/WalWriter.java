package ksh.tryptoengine.wal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import ksh.tryptoengine.event.EngineInboundEvent;
import ksh.tryptoengine.event.OrderCanceledEvent;
import ksh.tryptoengine.event.OrderPlacedEvent;
import ksh.tryptoengine.event.TickReceivedEvent;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Component
public class WalWriter {

    static final String WAL_FILE = "wal.log";
    static final String WAL_OLD_FILE = "wal.log.old";

    private final BlockingQueue<WalCommand> channel = new LinkedBlockingQueue<>(16384);
    private long sequence = 0;
    private final ObjectMapper mapper;
    private final Path walDir;

    private Thread thread;
    private volatile boolean running = true;
    private FileOutputStream out;
    private BufferedWriter writer;

    public WalWriter(ObjectMapper mapper, @Value("${engine.wal.dir}") String walDir) {
        this.mapper = mapper;
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
        while (running) {
            try {
                WalCommand cmd = channel.take();
                switch (cmd) {
                    case WalCommand.Write w -> writeLine(w.record());
                    case WalCommand.Rotate r -> {
                        try {
                            rotateFile();
                        } finally {
                            r.done().countDown();
                        }
                    }
                    case WalCommand.Flush f -> f.done().countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                log.error("WAL operation failed", e);
            }
        }
    }

    private void openWriter() throws IOException {
        Path file = walDir.resolve(WAL_FILE);
        out = new FileOutputStream(file.toFile(), true);
        writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
    }

    private void writeLine(WalRecord rec) throws IOException {
        String line = mapper.writeValueAsString(rec);
        writer.write(line);
        writer.newLine();
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
