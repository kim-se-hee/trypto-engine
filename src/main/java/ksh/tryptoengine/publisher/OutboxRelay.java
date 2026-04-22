package ksh.tryptoengine.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import ksh.tryptoengine.event.OrderFilledEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class OutboxRelay {

    private static final int BATCH_SIZE = 256;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbit;

    @Value("${engine.publisher.fanout-exchange}")
    private String fanoutExchange;

    public OutboxRelay(
        JdbcTemplate jdbc,
        @Qualifier("engineObjectMapper") ObjectMapper objectMapper,
        RabbitTemplate rabbit
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelay = 500)
    public void relay() {
        List<Row> rows = jdbc.query(
            "SELECT id, payload FROM outbox WHERE sent_at IS NULL ORDER BY id LIMIT ?",
            (rs, i) -> new Row(rs.getLong("id"), rs.getString("payload")),
            BATCH_SIZE
        );
        if (rows.isEmpty()) return;

        List<Long> sentIds = new ArrayList<>(rows.size());
        for (Row row : rows) {
            try {
                OrderFilledEvent event = objectMapper.readValue(row.payload, OrderFilledEvent.class);
                rabbit.convertAndSend(fanoutExchange, "", event);
                sentIds.add(row.id);
            } catch (Exception e) {
                log.warn("outbox relay publish failed id={}", row.id, e);
            }
        }
        if (sentIds.isEmpty()) return;

        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.batchUpdate(
            "UPDATE outbox SET sent_at = ? WHERE id = ?",
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    ps.setTimestamp(1, now);
                    ps.setLong(2, sentIds.get(i));
                }

                @Override
                public int getBatchSize() {
                    return sentIds.size();
                }
            }
        );
    }

    private record Row(long id, String payload) {
    }
}
