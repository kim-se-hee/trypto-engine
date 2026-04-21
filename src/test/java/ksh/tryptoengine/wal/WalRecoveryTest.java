package ksh.tryptoengine.wal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ksh.tryptoengine.dbwriter.DbWriterThread;
import ksh.tryptoengine.metrics.EngineMetrics;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WalRecoveryTest {

    @TempDir
    Path walDir;

    private ObjectMapper mapper;
    private ExchangeCoinResolver fakeResolver;
    private CapturingDbWriter dbWriter;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        fakeResolver = new ExchangeCoinResolver(null) {
            @Override
            public Long resolve(String exchange, String displayName) {
                return 1L;
            }
        };
        dbWriter = new CapturingDbWriter();
    }

    private static EngineMetrics newMetrics() {
        return new EngineMetrics(new SimpleMeterRegistry());
    }

    static class CapturingDbWriter extends DbWriterThread {
        final List<FillCommand> captured = new ArrayList<>();
        CapturingDbWriter() { super(null, null); }
        @Override
        public void offer(FillCommand cmd) { captured.add(cmd); }
    }

    @Test
    void 파일이_없으면_빈_상태와_seq0_을_반환() throws IOException {
        WalRecovery recovery = new WalRecovery(mapper, fakeResolver, dbWriter, walDir.toString());
        OrderBookRegistry state = new OrderBookRegistry();

        long seq = recovery.recover(state);

        assertThat(seq).isZero();
        assertThat(state.books()).isEmpty();
    }

    @Test
    void WAL만_있으면_전체_레코드_재생해서_상태_복구() throws IOException {
        WalWriter writer = new WalWriter(mapper, newMetrics(), walDir.toString());
        writer.start();
        writer.offer(placed(100L, "BUY", 1L, "7000", "1"));
        writer.offer(placed(101L, "SELL", 1L, "8000", "1"));
        writer.offer(canceled(100L, 1L));
        writer.stop();

        WalRecovery recovery = new WalRecovery(mapper, fakeResolver, dbWriter, walDir.toString());
        OrderBookRegistry state = new OrderBookRegistry();
        long seq = recovery.recover(state);

        assertThat(seq).isEqualTo(3);
        OrderBook book = state.bookOf(new TradingPair(1L));
        assertThat(book.size()).isEqualTo(1);
        assertThat(book.get(100L)).isNull();
        assertThat(book.get(101L)).isNotNull();
    }

    @Test
    void 스냅샷만_있으면_스냅샷_내용으로_복구() throws IOException {
        OrderBookRegistry original = new OrderBookRegistry();
        TradingPair pair = new TradingPair(1L);
        OrderBook book = original.bookOf(pair);
        book.tryAdd(detail(100L, Side.BUY, pair, "7000", "1"));
        book.tryAdd(detail(101L, Side.SELL, pair, "8500", "2"));

        SnapshotWriter snapshotWriter = new SnapshotWriter(mapper, walDir.toString());
        snapshotWriter.write(original, 42);

        WalRecovery recovery = new WalRecovery(mapper, fakeResolver, dbWriter, walDir.toString());
        OrderBookRegistry recovered = new OrderBookRegistry();
        long seq = recovery.recover(recovered);

        assertThat(seq).isEqualTo(42);
        OrderBook recoveredBook = recovered.bookOf(pair);
        assertThat(recoveredBook.size()).isEqualTo(2);
        assertThat(recoveredBook.get(100L)).isNotNull();
        assertThat(recoveredBook.get(101L)).isNotNull();
    }

    @Test
    void 스냅샷_이후_WAL_레코드만_추가_재생() throws IOException {
        OrderBookRegistry original = new OrderBookRegistry();
        TradingPair pair = new TradingPair(1L);
        OrderBook book = original.bookOf(pair);
        book.tryAdd(detail(100L, Side.BUY, pair, "7000", "1"));

        SnapshotWriter snapshotWriter = new SnapshotWriter(mapper, walDir.toString());
        snapshotWriter.write(original, 5);

        WalWriter writer = new WalWriter(mapper, newMetrics(), walDir.toString());
        writer.setSequence(5);
        writer.start();
        writer.offer(placed(200L, "SELL", 1L, "8500", "2"));
        writer.offer(placed(300L, "BUY", 1L, "7100", "0.5"));
        writer.offer(canceled(100L, 1L));
        writer.stop();

        WalRecovery recovery = new WalRecovery(mapper, fakeResolver, dbWriter, walDir.toString());
        OrderBookRegistry recovered = new OrderBookRegistry();
        long seq = recovery.recover(recovered);

        assertThat(seq).isEqualTo(8);
        OrderBook recoveredBook = recovered.bookOf(pair);
        assertThat(recoveredBook.size()).isEqualTo(2);
        assertThat(recoveredBook.get(100L)).isNull();
        assertThat(recoveredBook.get(200L)).isNotNull();
        assertThat(recoveredBook.get(300L)).isNotNull();
    }

    @Test
    void 체크포인트_사이클_스냅샷_로테이트_추가쓰기_복구() throws IOException {
        WalWriter writer = new WalWriter(mapper, newMetrics(), walDir.toString());
        SnapshotWriter snapshotWriter = new SnapshotWriter(mapper, walDir.toString());
        OrderBookRegistry liveState = new OrderBookRegistry();

        writer.start();

        for (long id = 100; id <= 102; id++) {
            OrderPlacedEvent ev = placed(id, "BUY", 1L, "7000", "1");
            writer.offer(ev);
            applyPlaced(liveState, ev);
        }

        long seqAtCheckpoint = writer.currentSequence();
        snapshotWriter.write(liveState, seqAtCheckpoint);
        writer.rotate();

        for (long id = 200; id <= 201; id++) {
            OrderPlacedEvent ev = placed(id, "SELL", 1L, "8500", "2");
            writer.offer(ev);
            applyPlaced(liveState, ev);
        }
        writer.stop();

        WalRecovery recovery = new WalRecovery(mapper, fakeResolver, dbWriter, walDir.toString());
        OrderBookRegistry recovered = new OrderBookRegistry();
        long recoveredSeq = recovery.recover(recovered);

        assertThat(recoveredSeq).isEqualTo(5);
        OrderBook recoveredBook = recovered.bookOf(new TradingPair(1L));
        assertThat(recoveredBook.size()).isEqualTo(5);
        for (long id : new long[]{100, 101, 102, 200, 201}) {
            assertThat(recoveredBook.get(id)).as("order %d", id).isNotNull();
        }
    }

    @Test
    void 틱_이벤트_재생은_sweep_결과를_dbWriter로_offer한다() throws IOException {
        WalWriter writer = new WalWriter(mapper, newMetrics(), walDir.toString());
        writer.start();
        writer.offer(placed(100L, "BUY", 1L, "7000", "1"));
        writer.offer(tick("UPBIT", "BTC", "6900"));
        writer.stop();

        WalRecovery recovery = new WalRecovery(mapper, fakeResolver, dbWriter, walDir.toString());
        OrderBookRegistry state = new OrderBookRegistry();
        long seq = recovery.recover(state);

        assertThat(seq).isEqualTo(2);
        OrderBook book = state.bookOf(new TradingPair(1L));
        assertThat(book.size()).isZero();
        assertThat(dbWriter.captured).hasSize(1);
        assertThat(dbWriter.captured.get(0).order().orderId()).isEqualTo(100L);
        assertThat(dbWriter.captured.get(0).executedPrice()).isEqualByComparingTo("6900");
    }

    @Test
    void 복구된_seq_는_이어서_채번되어_중복_없음() throws IOException {
        WalWriter writer1 = new WalWriter(mapper, newMetrics(), walDir.toString());
        writer1.start();
        writer1.offer(placed(100L, "BUY", 1L, "7000", "1"));
        writer1.offer(placed(101L, "BUY", 1L, "7100", "1"));
        writer1.stop();

        WalRecovery recovery = new WalRecovery(mapper, fakeResolver, dbWriter, walDir.toString());
        OrderBookRegistry state = new OrderBookRegistry();
        long lastSeq = recovery.recover(state);

        WalWriter writer2 = new WalWriter(mapper, newMetrics(), walDir.toString());
        writer2.setSequence(lastSeq);
        writer2.start();
        long newSeq = writer2.offer(placed(102L, "BUY", 1L, "7200", "1"));
        writer2.stop();

        assertThat(lastSeq).isEqualTo(2);
        assertThat(newSeq).isEqualTo(3);
    }

    private OrderPlacedEvent placed(long id, String side, long exchangeCoinId, String price, String qty) {
        BigDecimal p = new BigDecimal(price);
        BigDecimal q = new BigDecimal(qty);
        return new OrderPlacedEvent(
            id, 10L, 20L, side, exchangeCoinId, 2L, 1L,
            p, q, p.multiply(q),
            side.equals("BUY") ? 1L : 2L,
            LocalDateTime.of(2026, 1, 1, 0, 0, 0)
        );
    }

    private OrderCanceledEvent canceled(long id, long exchangeCoinId) {
        return new OrderCanceledEvent(id, exchangeCoinId);
    }

    private TickReceivedEvent tick(String exchange, String displayName, String price) {
        return new TickReceivedEvent(exchange, displayName, new BigDecimal(price),
            LocalDateTime.of(2026, 1, 1, 1, 0, 0));
    }

    private OrderDetail detail(long id, Side side, TradingPair pair, String price, String qty) {
        BigDecimal p = new BigDecimal(price);
        BigDecimal q = new BigDecimal(qty);
        return new OrderDetail(
            id, 10L, 20L, side, pair, 2L, 1L,
            p, q, p.multiply(q),
            side == Side.BUY ? 1L : 2L,
            LocalDateTime.of(2026, 1, 1, 0, 0, 0)
        );
    }

    private void applyPlaced(OrderBookRegistry state, OrderPlacedEvent p) {
        TradingPair pair = new TradingPair(p.exchangeCoinId());
        OrderBook book = state.bookOf(pair);
        book.tryAdd(new OrderDetail(
            p.orderId(), p.userId(), p.walletId(),
            Side.valueOf(p.side()), pair,
            p.coinId(), p.baseCoinId(),
            p.price(), p.quantity(), p.lockedAmount(), p.lockedCoinId(),
            p.placedAt()
        ));
    }
}
