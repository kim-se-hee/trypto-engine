package ksh.tryptoengine.engine;

import java.util.HashMap;
import java.util.Map;

public class OrderBookRegistry {

    private final Map<TradingPair, OrderBook> books = new HashMap<>();

    public OrderBook bookOf(TradingPair pair) {
        return books.computeIfAbsent(pair, OrderBook::new);
    }

    public Map<TradingPair, OrderBook> books() {
        return books;
    }
}
