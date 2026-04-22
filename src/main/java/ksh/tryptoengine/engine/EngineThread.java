package ksh.tryptoengine.engine;

import io.micrometer.core.instrument.Gauge;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import ksh.tryptoengine.dbwriter.DbWriterThread;
import ksh.tryptoengine.event.EngineInboundEvent;
import ksh.tryptoengine.event.FillCommand;
import ksh.tryptoengine.event.OrderCanceledEvent;
import ksh.tryptoengine.event.OrderPlacedEvent;
import ksh.tryptoengine.event.TickReceivedEvent;
import ksh.tryptoengine.metrics.EngineMetrics;
import ksh.tryptoengine.wal.SnapshotWriter;
import ksh.tryptoengine.wal.WalRecovery;
import ksh.tryptoengine.wal.WalWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Component
@RequiredArgsConstructor
public class EngineThread {

    private final WalWriter walWriter;
    private final WalRecovery walRecovery;
    private final SnapshotWriter snapshotWriter;
    private final DbWriterThread dbWriter;
    private final ExchangeCoinResolver exchangeCoinResolver;
    private final EngineMetrics metrics;
    private final OrderBookRegistry orderBookRegistry = new OrderBookRegistry();
    private final BlockingQueue<EngineInboundEvent> inbox = new LinkedBlockingQueue<>(16384);

    @Value("${engine.wal.checkpoint-interval-events:1000}")
    private int checkpointIntervalEvents;

    private Thread thread;
    private volatile boolean running = true;
    private int eventsSinceCheckpoint = 0;

    @PostConstruct
    public void init() throws Exception {
        dbWriter.start();
        long lastSeq = walRecovery.recover(orderBookRegistry);
        walWriter.setSequence(lastSeq);
        walWriter.start();
        Gauge.builder("engine.queue.size", inbox, BlockingQueue::size)
            .description("Engine inbox depth")
            .register(metrics.registry());
        thread = new Thread(this::loop, "engine-core");
        thread.setDaemon(false);
        thread.start();
        log.info("engine thread started (recovered lastSeq={})", lastSeq);
    }

    public void submit(EngineInboundEvent event) {
        try {
            inbox.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("engine submit interrupted", e);
        }
    }

    private void loop() {
        while (running) {
            try {
                EngineInboundEvent event = inbox.take();
                walWriter.offer(event);
                switch (event) {
                    case OrderPlacedEvent p -> onPlaced(p);
                    case OrderCanceledEvent c -> onCanceled(c);
                    case TickReceivedEvent t -> onTick(t);
                }
                if (++eventsSinceCheckpoint >= checkpointIntervalEvents) {
                    checkpoint();
                    eventsSinceCheckpoint = 0;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("engine processing failed", e);
            }
        }
    }

    private void checkpoint() {
        try {
            long seq = walWriter.currentSequence();
            snapshotWriter.write(orderBookRegistry, seq);
            walWriter.rotate();
            log.info("checkpoint done seq={}", seq);
        } catch (Exception e) {
            log.error("checkpoint failed", e);
        }
    }

    private void onPlaced(OrderPlacedEvent e) {
        TradingPair pair = new TradingPair(e.exchangeCoinId());
        OrderDetail detail = new OrderDetail(
            e.orderId(), e.userId(), e.walletId(),
            Side.valueOf(e.side()), pair,
            e.coinId(), e.baseCoinId(),
            e.price(), e.quantity(), e.lockedAmount(), e.lockedCoinId(), e.placedAt()
        );
        OrderBook book = orderBookRegistry.bookOf(pair);
        if (!book.tryAdd(detail)) {
            log.debug("duplicate OrderPlaced ignored orderId={}", e.orderId());
        }
    }

    private void onCanceled(OrderCanceledEvent e) {
        TradingPair pair = new TradingPair(e.exchangeCoinId());
        OrderBook book = orderBookRegistry.bookOf(pair);
        if (book.tryRemove(e.orderId()) == null) {
            log.debug("cancel for unknown/duplicate orderId={}", e.orderId());
        }
    }

    private void onTick(TickReceivedEvent e) {
        Long exchangeCoinId = exchangeCoinResolver.resolve(e.exchange(), e.displayName());
        if (exchangeCoinId == null) {
            return;
        }
        TradingPair pair = new TradingPair(exchangeCoinId);
        OrderBook book = orderBookRegistry.bookOf(pair);
        List<OrderDetail> triggered = book.sweep(e.tradePrice());
        LocalDateTime ts = e.tickAt() != null ? e.tickAt() : LocalDateTime.now();
        for (OrderDetail o : triggered) {
            FillCommand cmd = new FillCommand(o, e.tradePrice(), ts);
            dbWriter.offer(cmd);
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }
}
