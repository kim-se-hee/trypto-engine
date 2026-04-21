package ksh.tryptoengine.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderPlacedEvent(
    Long orderId,
    Long userId,
    Long walletId,
    String side,
    Long exchangeCoinId,
    Long coinId,
    Long baseCoinId,
    BigDecimal price,
    BigDecimal quantity,
    BigDecimal lockedAmount,
    Long lockedCoinId,
    LocalDateTime placedAt
) implements EngineInboundEvent {
}
