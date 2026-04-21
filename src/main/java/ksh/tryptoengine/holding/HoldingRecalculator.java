package ksh.tryptoengine.holding;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * orders 테이블의 FILLED row 들을 체결시각 오름차순으로 재생하여
 * holding 의 avg_buy_price / total_quantity / total_buy_amount / averaging_down_count 를
 * 통째로 재계산 후 upsert. 파생 상태로 다루므로 순서 의존 제거.
 */
@Component
@RequiredArgsConstructor
public class HoldingRecalculator {

    private final JdbcTemplate jdbc;

    public void recalculate(Long walletId, Long coinId) {
        List<Map<String, Object>> fills = jdbc.queryForList(
            "SELECT side, filled_price AS price, quantity FROM orders " +
                "WHERE wallet_id=? AND coin_id=? AND status='FILLED' " +
                "ORDER BY filled_at ASC, order_id ASC",
            walletId, coinId
        );
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal totalBuy = BigDecimal.ZERO;
        BigDecimal avg = BigDecimal.ZERO;
        int averagingDownCount = 0;
        for (Map<String, Object> f : fills) {
            BigDecimal p = (BigDecimal) f.get("price");
            BigDecimal q = (BigDecimal) f.get("quantity");
            if ("BUY".equals(f.get("side"))) {
                BigDecimal newQty = qty.add(q);
                BigDecimal newAvg = qty.signum() == 0
                    ? p
                    : avg.multiply(qty).add(p.multiply(q)).divide(newQty, 8, RoundingMode.HALF_UP);
                if (qty.signum() > 0 && newAvg.compareTo(avg) < 0) {
                    averagingDownCount++;
                }
                totalBuy = totalBuy.add(p.multiply(q));
                qty = newQty;
                avg = newAvg;
            } else {
                qty = qty.subtract(q);
            }
        }
        jdbc.update(
            "INSERT INTO holding (wallet_id, coin_id, avg_buy_price, total_quantity, total_buy_amount, " +
                "averaging_down_count, version) VALUES (?, ?, ?, ?, ?, ?, 0) " +
                "ON DUPLICATE KEY UPDATE avg_buy_price=VALUES(avg_buy_price), " +
                "total_quantity=VALUES(total_quantity), total_buy_amount=VALUES(total_buy_amount), " +
                "averaging_down_count=VALUES(averaging_down_count), version=version+1",
            walletId, coinId, avg, qty, totalBuy, averagingDownCount
        );
    }
}
