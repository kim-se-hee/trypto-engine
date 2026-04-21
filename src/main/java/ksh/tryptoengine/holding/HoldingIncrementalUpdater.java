package ksh.tryptoengine.holding;

import ksh.tryptoengine.engine.OrderDetail;
import ksh.tryptoengine.engine.Side;
import ksh.tryptoengine.event.FillCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class HoldingIncrementalUpdater {

    static final HoldingState EMPTY = new HoldingState(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);

    private final JdbcTemplate jdbc;

    public void apply(List<FillCommand> fills) {
        Map<Key, List<FillCommand>> grouped = new LinkedHashMap<>();
        for (FillCommand c : fills) {
            OrderDetail o = c.order();
            grouped.computeIfAbsent(new Key(o.walletId(), o.coinId()), k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<Key, List<FillCommand>> e : grouped.entrySet()) {
            applyOne(e.getKey(), e.getValue());
        }
    }

    private void applyOne(Key key, List<FillCommand> fills) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT avg_buy_price, total_quantity, total_buy_amount, averaging_down_count " +
                "FROM holding WHERE wallet_id=? AND coin_id=?",
            key.walletId(), key.coinId()
        );

        HoldingState start;
        if (rows.isEmpty()) {
            start = EMPTY;
        } else {
            Map<String, Object> r = rows.get(0);
            start = new HoldingState(
                (BigDecimal) r.get("avg_buy_price"),
                (BigDecimal) r.get("total_quantity"),
                (BigDecimal) r.get("total_buy_amount"),
                ((Number) r.get("averaging_down_count")).intValue()
            );
        }

        HoldingState next = applyFills(start, fills);

        jdbc.update(
            "INSERT INTO holding (wallet_id, coin_id, avg_buy_price, total_quantity, total_buy_amount, " +
                "averaging_down_count, version) VALUES (?, ?, ?, ?, ?, ?, 0) " +
                "ON DUPLICATE KEY UPDATE avg_buy_price=VALUES(avg_buy_price), " +
                "total_quantity=VALUES(total_quantity), total_buy_amount=VALUES(total_buy_amount), " +
                "averaging_down_count=VALUES(averaging_down_count), version=version+1",
            key.walletId(), key.coinId(), next.avg(), next.qty(), next.totalBuy(), next.adCount()
        );
    }

    static HoldingState applyFills(HoldingState start, List<FillCommand> fills) {
        BigDecimal avg = start.avg();
        BigDecimal qty = start.qty();
        BigDecimal totalBuy = start.totalBuy();
        int adCount = start.adCount();

        for (FillCommand cmd : fills) {
            OrderDetail o = cmd.order();
            BigDecimal p = cmd.executedPrice();
            BigDecimal q = o.quantity();
            if (o.side() == Side.BUY) {
                BigDecimal newQty = qty.add(q);
                BigDecimal newAvg = qty.signum() == 0
                    ? p
                    : avg.multiply(qty).add(p.multiply(q)).divide(newQty, 8, RoundingMode.HALF_UP);
                if (qty.signum() > 0 && newAvg.compareTo(avg) < 0) {
                    adCount++;
                }
                totalBuy = totalBuy.add(p.multiply(q));
                qty = newQty;
                avg = newAvg;
            } else {
                qty = qty.subtract(q);
            }
        }

        return new HoldingState(avg, qty, totalBuy, adCount);
    }

    record HoldingState(BigDecimal avg, BigDecimal qty, BigDecimal totalBuy, int adCount) {}

    private record Key(Long walletId, Long coinId) {}
}
