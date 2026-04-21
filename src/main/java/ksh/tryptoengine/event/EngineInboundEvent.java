package ksh.tryptoengine.event;

public sealed interface EngineInboundEvent
    permits OrderPlacedEvent, OrderCanceledEvent, TickReceivedEvent {
}
