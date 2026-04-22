package ksh.tryptoengine.ingress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ksh.tryptoengine.engine.EngineThread;
import ksh.tryptoengine.event.EngineInboundEvent;
import ksh.tryptoengine.event.OrderCanceledEvent;
import ksh.tryptoengine.event.OrderPlacedEvent;
import ksh.tryptoengine.event.TickReceivedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitIngress {

    private final ObjectMapper mapper;
    private final EngineThread engine;

    @RabbitListener(queues = "${engine.inbox.queue}", concurrency = "1")
    public void onMessage(byte[] payload, @org.springframework.messaging.handler.annotation.Header(
        value = "event_type", required = false) String eventType) throws Exception {
        if (eventType == null) {
            log.warn("engine.inbox message missing event_type header, skip");
            return;
        }
        JsonNode node = mapper.readTree(payload);
        EngineInboundEvent event = switch (eventType) {
            case "OrderPlaced" -> mapper.treeToValue(node, OrderPlacedEvent.class);
            case "OrderCanceled" -> mapper.treeToValue(node, OrderCanceledEvent.class);
            case "TickReceived" -> mapper.treeToValue(node, TickReceivedEvent.class);
            default -> {
                log.warn("unknown event_type={}", eventType);
                yield null;
            }
        };
        if (event != null) engine.submit(event);
    }
}
