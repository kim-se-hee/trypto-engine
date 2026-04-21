package ksh.tryptoengine.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBookTest {

    private final TradingPair pair = new TradingPair(1L);

    @Test
    void 중복_OrderId_는_삽입되지_않는다() {
        OrderBook book = new OrderBook(pair);
        OrderDetail first = order(100L, Side.BUY, "7000", "1");
        OrderDetail duplicate = order(100L, Side.BUY, "7000", "1");

        assertThat(book.tryAdd(first)).isTrue();
        assertThat(book.tryAdd(duplicate)).isFalse();
        assertThat(book.size()).isEqualTo(1);
    }

    @Test
    void 취소는_orderIndex_를_통해_위치를_찾아_제거한다() {
        OrderBook book = new OrderBook(pair);
        book.tryAdd(order(100L, Side.BUY, "7000", "1"));
        book.tryAdd(order(101L, Side.BUY, "7000", "1"));
        book.tryAdd(order(200L, Side.SELL, "8000", "1"));

        OrderDetail removed = book.tryRemove(100L);

        assertThat(removed).isNotNull();
        assertThat(book.size()).isEqualTo(2);
        assertThat(book.get(100L)).isNull();
    }

    @Test
    void 매수_스윕은_틱_이하_가격_전부_트리거() {
        OrderBook book = new OrderBook(pair);
        book.tryAdd(order(1L, Side.BUY, "7000", "1"));
        book.tryAdd(order(2L, Side.BUY, "6900", "1"));
        book.tryAdd(order(3L, Side.BUY, "6800", "1"));
        book.tryAdd(order(4L, Side.SELL, "7500", "1"));

        List<OrderDetail> hit = book.sweep(new BigDecimal("6900"));

        assertThat(hit).extracting(OrderDetail::orderId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(book.size()).isEqualTo(2);
    }

    @Test
    void 매도_스윕은_틱_이상_가격_전부_트리거() {
        OrderBook book = new OrderBook(pair);
        book.tryAdd(order(1L, Side.SELL, "7500", "1"));
        book.tryAdd(order(2L, Side.SELL, "7600", "1"));
        book.tryAdd(order(3L, Side.BUY, "6800", "1"));

        List<OrderDetail> hit = book.sweep(new BigDecimal("7600"));

        assertThat(hit).extracting(OrderDetail::orderId).containsExactlyInAnyOrder(1L, 2L);
    }

    private OrderDetail order(Long id, Side side, String price, String qty) {
        return new OrderDetail(
            id, 10L, 20L, side, pair, 2L, 1L,
            new BigDecimal(price), new BigDecimal(qty),
            new BigDecimal(qty).multiply(new BigDecimal(price)),
            side == Side.BUY ? 1L : 2L,
            LocalDateTime.now()
        );
    }
}
