package ksh.tryptoengine.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public class OrderBook {

    private final TradingPair pair;
    private final NavigableMap<BigDecimal, List<Long>> bids = new TreeMap<>(Collections.reverseOrder());
    private final NavigableMap<BigDecimal, List<Long>> asks = new TreeMap<>();
    private final Map<Long, OrderDetail> orderIndex = new HashMap<>();

    public OrderBook(TradingPair pair) {
        this.pair = pair;
    }

    public boolean tryAdd(OrderDetail detail) {
        if (orderIndex.containsKey(detail.orderId())) {
            return false;
        }
        orderIndex.put(detail.orderId(), detail);
        book(detail.side()).computeIfAbsent(detail.price(), k -> new ArrayList<>()).add(detail.orderId());
        return true;
    }

    public OrderDetail tryRemove(Long orderId) {
        OrderDetail detail = orderIndex.remove(orderId);
        if (detail == null) {
            return null;
        }
        NavigableMap<BigDecimal, List<Long>> side = book(detail.side());
        List<Long> bucket = side.get(detail.price());
        if (bucket != null) {
            bucket.remove(orderId);
            if (bucket.isEmpty()) {
                side.remove(detail.price());
            }
        }
        return detail;
    }

    public List<OrderDetail> sweep(BigDecimal tickPrice) {
        List<OrderDetail> triggered = new ArrayList<>();
        collectBuys(tickPrice, triggered);
        collectSells(tickPrice, triggered);
        triggered.forEach(d -> tryRemove(d.orderId()));
        return triggered;
    }

    public OrderDetail get(Long orderId) {
        return orderIndex.get(orderId);
    }

    public TradingPair pair() {
        return pair;
    }

    public int size() {
        return orderIndex.size();
    }

    public Collection<OrderDetail> allOrders() {
        return orderIndex.values();
    }

    private void collectBuys(BigDecimal tickPrice, List<OrderDetail> out) {
        for (Map.Entry<BigDecimal, List<Long>> e : bids.entrySet()) {
            if (e.getKey().compareTo(tickPrice) < 0) break;
            for (Long id : e.getValue()) {
                OrderDetail d = orderIndex.get(id);
                if (d != null) out.add(d);
            }
        }
    }

    private void collectSells(BigDecimal tickPrice, List<OrderDetail> out) {
        for (Map.Entry<BigDecimal, List<Long>> e : asks.entrySet()) {
            if (e.getKey().compareTo(tickPrice) > 0) break;
            for (Long id : e.getValue()) {
                OrderDetail d = orderIndex.get(id);
                if (d != null) out.add(d);
            }
        }
    }

    private NavigableMap<BigDecimal, List<Long>> book(Side side) {
        return side == Side.BUY ? bids : asks;
    }
}
