package ksh.tryptoengine.event;

import ksh.tryptoengine.engine.OrderDetail;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record FillCommand(
    OrderDetail order,
    BigDecimal executedPrice,
    LocalDateTime executedAt
) {
}
