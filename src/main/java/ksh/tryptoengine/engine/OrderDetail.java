package ksh.tryptoengine.engine;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderDetail(
    Long orderId,
    Long userId,
    Long walletId,
    Side side,
    TradingPair pair,
    Long coinId,
    Long baseCoinId,
    BigDecimal price,
    BigDecimal quantity,
    BigDecimal lockedAmount,
    Long lockedCoinId,
    LocalDateTime placedAt
) {
}
