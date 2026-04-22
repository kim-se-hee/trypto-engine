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
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(value = 1, jvmArgsAppend = {"-Xms4g", "-Xmx10g"})
public class OrderBookBenchmark {

    private static final int PRICE_BUCKET_SIZE = 1024;
    private static final int PRICE_BUCKET_MASK = PRICE_BUCKET_SIZE - 1;
    private static final LocalDateTime FIXED_TS = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal MID = new BigDecimal("100.00");

    @Param({"10000", "100000", "1000000", "10000000"})
    public int bookSize;

    private OrderBook book;
    private TradingPair pair;
    private BigDecimal[] bidPrices;
    private BigDecimal[] askPrices;
    private long nextOrderId;

    @Setup(Level.Iteration)
    public void setup() {
        pair = new TradingPair(1L);
        book = new OrderBook(pair);

        RandomGenerator r = RandomGeneratorFactory.of("L64X128MixRandom").create(42);

        bidPrices = new BigDecimal[PRICE_BUCKET_SIZE];
        askPrices = new BigDecimal[PRICE_BUCKET_SIZE];
        for (int i = 0; i < PRICE_BUCKET_SIZE; i++) {
            bidPrices[i] = BigDecimal.valueOf(5000 + r.nextInt(4999), 2);
            askPrices[i] = BigDecimal.valueOf(10001 + r.nextInt(4999), 2);
        }

        nextOrderId = 1;
        for (int i = 0; i < bookSize; i++) {
            boolean bid = (i & 1) == 0;
            BigDecimal price = bid
                ? bidPrices[i & PRICE_BUCKET_MASK]
                : askPrices[i & PRICE_BUCKET_MASK];
            book.tryAdd(makeOrder(nextOrderId++, bid ? Side.BUY : Side.SELL, price));
        }
    }

    @Benchmark
    public boolean addOrder() {
        long id = nextOrderId++;
        boolean bid = (id & 1L) == 0;
        BigDecimal price = bid
            ? bidPrices[(int) id & PRICE_BUCKET_MASK]
            : askPrices[(int) id & PRICE_BUCKET_MASK];
        return book.tryAdd(makeOrder(id, bid ? Side.BUY : Side.SELL, price));
    }

    @Benchmark
    public void sweepMiss(Blackhole bh) {
        bh.consume(book.sweep(MID));
    }

    @Benchmark
    public OrderDetail addAndRemove() {
        long id = nextOrderId++;
        boolean bid = (id & 1L) == 0;
        BigDecimal price = bid
            ? bidPrices[(int) id & PRICE_BUCKET_MASK]
            : askPrices[(int) id & PRICE_BUCKET_MASK];
        book.tryAdd(makeOrder(id, bid ? Side.BUY : Side.SELL, price));
        return book.tryRemove(id);
    }

    private OrderDetail makeOrder(long id, Side side, BigDecimal price) {
        return new OrderDetail(
            id, 1L, 1L, side, pair,
            1L, 2L,
            price, ONE, ONE, 2L,
            FIXED_TS
        );
    }
}
