package ksh.tryptoengine.event;

public record OrderCanceledEvent(
    Long orderId,
    Long exchangeCoinId
) implements EngineInboundEvent {
}
