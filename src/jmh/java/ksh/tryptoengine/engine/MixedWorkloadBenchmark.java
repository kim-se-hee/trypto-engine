package ksh.tryptoengine.engine;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(value = 1, jvmArgsAppend = {"-Xms4g", "-Xmx10g"})
public class MixedWorkloadBenchmark {

    private static final int PRICE_SIZE = 1024;
    private static final int PRICE_MASK = PRICE_SIZE - 1;
    private static final int OP_SIZE = 1024;
    private static final int OP_MASK = OP_SIZE - 1;
    private static final int ACTIVE_CAP = 1 << 24;
    private static final int ACTIVE_MASK = ACTIVE_CAP - 1;
    private static final LocalDateTime FIXED_TS = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final BigDecimal ONE = BigDecimal.ONE;

    @Param({"10000", "100000", "1000000", "10000000"})
    public int initialBookSize;

    private OrderBook book;
    private TradingPair pair;
    private BigDecimal[] bidPrices;
    private BigDecimal[] askPrices;
    private BigDecimal[] tickPrices;
    private byte[] opSeq;
    private long[] activeIds;
    private int activeHead;
    private int activeSize;
    private long nextOrderId;
    private int seqIdx;

    @Setup(Level.Iteration)
    public void setup() {
        pair = new TradingPair(1L);
        book = new OrderBook(pair);
        RandomGenerator r = RandomGeneratorFactory.of("L64X128MixRandom").create(42);

        bidPrices = new BigDecimal[PRICE_SIZE];
        askPrices = new BigDecimal[PRICE_SIZE];
        for (int i = 0; i < PRICE_SIZE; i++) {
            bidPrices[i] = BigDecimal.valueOf(9500 + r.nextInt(499), 2);
            askPrices[i] = BigDecimal.valueOf(10001 + r.nextInt(499), 2);
        }

        tickPrices = new BigDecimal[PRICE_SIZE];
        BigDecimal mid = BigDecimal.valueOf(10000, 2);
        BigDecimal justBelow = BigDecimal.valueOf(9999, 2);
        BigDecimal justAbove = BigDecimal.valueOf(10001, 2);
        for (int i = 0; i < PRICE_SIZE; i++) {
            int pick = r.nextInt(10);
            if (pick < 8) tickPrices[i] = mid;
            else if (pick == 8) tickPrices[i] = justBelow;
            else tickPrices[i] = justAbove;
        }

        opSeq = new byte[OP_SIZE];
        for (int i = 0; i < OP_SIZE; i++) {
            int pick = r.nextInt(100);
            if (pick < 50) opSeq[i] = 0;
            else if (pick < 70) opSeq[i] = 1;
            else opSeq[i] = 2;
        }

        activeIds = new long[ACTIVE_CAP];
        activeHead = 0;
        activeSize = 0;
        nextOrderId = 1;

        for (int i = 0; i < initialBookSize; i++) {
            boolean bid = (i & 1) == 0;
            BigDecimal price = bid ? bidPrices[i & PRICE_MASK] : askPrices[i & PRICE_MASK];
            long id = nextOrderId++;
            if (book.tryAdd(makeOrder(id, bid ? Side.BUY : Side.SELL, price))) {
                pushActive(id);
            }
        }
        seqIdx = 0;
    }

    @Benchmark
    public void mixed(Blackhole bh) {
        byte op = opSeq[seqIdx++ & OP_MASK];
        switch (op) {
            case 0 -> {
                long id = nextOrderId++;
                boolean bid = (id & 1L) == 0;
                BigDecimal price = bid
                    ? bidPrices[(int) id & PRICE_MASK]
                    : askPrices[(int) id & PRICE_MASK];
                if (book.tryAdd(makeOrder(id, bid ? Side.BUY : Side.SELL, price))) {
                    pushActive(id);
                }
            }
            case 1 -> {
                long id = popActive();
                if (id != 0L) {
                    bh.consume(book.tryRemove(id));
                }
            }
            case 2 -> {
                BigDecimal tick = tickPrices[seqIdx & PRICE_MASK];
                bh.consume(book.sweep(tick));
            }
        }
    }

    private void pushActive(long id) {
        if (activeSize < ACTIVE_CAP) {
            activeIds[(activeHead + activeSize) & ACTIVE_MASK] = id;
            activeSize++;
        }
    }

    private long popActive() {
        if (activeSize == 0) return 0L;
        long id = activeIds[activeHead];
        activeHead = (activeHead + 1) & ACTIVE_MASK;
        activeSize--;
        return id;
    }

    private OrderDetail makeOrder(long id, Side side, BigDecimal price) {
        return new OrderDetail(id, 1L, 1L, side, pair, 1L, 2L, price, ONE, ONE, 2L, FIXED_TS);
    }
}
