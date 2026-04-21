package ksh.tryptoengine.wal;

import com.fasterxml.jackson.databind.ObjectMapper;
import ksh.tryptoengine.dbwriter.DbWriterThread;
import ksh.tryptoengine.engine.OrderBookRegistry;
import ksh.tryptoengine.engine.ExchangeCoinResolver;
import ksh.tryptoengine.engine.OrderBook;
import ksh.tryptoengine.engine.OrderDetail;
import ksh.tryptoengine.engine.Side;
import ksh.tryptoengine.engine.TradingPair;
import ksh.tryptoengine.event.FillCommand;
import ksh.tryptoengine.event.OrderCanceledEvent;
import ksh.tryptoengine.event.OrderPlacedEvent;
import ksh.tryptoengine.event.TickReceivedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class WalRecovery {

    private final ObjectMapper mapper;
    private final ExchangeCoinResolver resolver;
    private final DbWriterThread dbWriter;
    private final Path walDir;

    public WalRecovery(ObjectMapper mapper, ExchangeCoinResolver resolver,
                       DbWriterThread dbWriter,
                       @Value("${engine.wal.dir}") String walDir) {
        this.mapper = mapper;
        this.resolver = resolver;
        this.dbWriter = dbWriter;
        this.walDir = Path.of(walDir);
    }

    public long recover(OrderBookRegistry registry) throws IOException {
        if (!Files.exists(walDir)) {
            return 0;
        }
        long lastSeq = 0;

        Path snapshotPath = walDir.resolve(SnapshotWriter.SNAPSHOT_FILE);
        if (Files.exists(snapshotPath)) {
            Snapshot snap = mapper.readValue(snapshotPath.toFile(), Snapshot.class);
            for (Snapshot.PairSnapshot ps : snap.pairs()) {
                OrderBook book = registry.bookOf(new TradingPair(ps.exchangeCoinId()));
                for (OrderDetail o : ps.orders()) book.tryAdd(o);
            }
            lastSeq = snap.lastSeq();
            log.info("snapshot loaded lastSeq={} pairs={}", lastSeq, snap.pairs().size());
        }

        Path oldWal = walDir.resolve(WalWriter.WAL_OLD_FILE);
        if (Files.exists(oldWal)) {
            lastSeq = replayFile(oldWal, lastSeq, registry);
            Files.deleteIfExists(oldWal);
        }

        Path walFile = walDir.resolve(WalWriter.WAL_FILE);
        if (Files.exists(walFile)) {
            lastSeq = replayFile(walFile, lastSeq, registry);
        }
        log.info("wal recovery done lastSeq={}", lastSeq);
        return lastSeq;
    }

    private long replayFile(Path file, long skipUpTo, OrderBookRegistry registry) {
        long maxSeq = skipUpTo;
        int applied = 0;
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                WalRecord rec;
                try {
                    rec = mapper.readValue(line, WalRecord.class);
                } catch (IOException e) {
                    log.warn("WAL line parse failed (likely truncated tail); stop replay file={}", file.getFileName());
                    break;
                }
                if (rec.sequence() > skipUpTo) {
                    apply(rec, registry);
                    applied++;
                    if (rec.sequence() > maxSeq) maxSeq = rec.sequence();
                }
            }
        } catch (IOException e) {
            log.warn("WAL read error file={}", file, e);
        }
        log.info("replayed file={} applied={} lastSeq={}", file.getFileName(), applied, maxSeq);
        return maxSeq;
    }

    private void apply(WalRecord rec, OrderBookRegistry registry) throws IOException {
        switch (rec.eventType()) {
            case "OrderPlaced" -> {
                OrderPlacedEvent p = mapper.treeToValue(rec.event(), OrderPlacedEvent.class);
                TradingPair pair = new TradingPair(p.exchangeCoinId());
                OrderBook book = registry.bookOf(pair);
                book.tryAdd(new OrderDetail(
                    p.orderId(), p.userId(), p.walletId(),
                    Side.valueOf(p.side()), pair,
                    p.coinId(), p.baseCoinId(),
                    p.price(), p.quantity(), p.lockedAmount(), p.lockedCoinId(),
                    p.placedAt()
                ));
            }
            case "OrderCanceled" -> {
                OrderCanceledEvent c = mapper.treeToValue(rec.event(), OrderCanceledEvent.class);
                OrderBook book = registry.bookOf(new TradingPair(c.exchangeCoinId()));
                book.tryRemove(c.orderId());
            }
            case "TickReceived" -> {
                TickReceivedEvent t = mapper.treeToValue(rec.event(), TickReceivedEvent.class);
                Long ecId = resolver.resolve(t.exchange(), t.displayName());
                if (ecId == null) return;
                OrderBook book = registry.bookOf(new TradingPair(ecId));
                List<OrderDetail> triggered = book.sweep(t.tradePrice());
                LocalDateTime ts = t.tickAt() != null ? t.tickAt() : LocalDateTime.now();
                for (OrderDetail o : triggered) {
                    dbWriter.offer(new FillCommand(o, t.tradePrice(), ts));
                }
            }
            default -> log.warn("unknown wal event type={} seq={}", rec.eventType(), rec.sequence());
        }
    }
}
