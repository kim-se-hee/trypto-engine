package ksh.tryptoengine.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TickReceivedEvent(
    String exchange,
    String displayName,
    BigDecimal tradePrice,
    LocalDateTime tickAt
) implements EngineInboundEvent {
}
