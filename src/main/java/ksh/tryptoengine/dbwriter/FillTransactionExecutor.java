package ksh.tryptoengine.dbwriter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ksh.tryptoengine.engine.OrderDetail;
import ksh.tryptoengine.event.FillCommand;
import ksh.tryptoengine.event.OrderFilledEvent;
import ksh.tryptoengine.holding.HoldingIncrementalUpdater;
import ksh.tryptoengine.metrics.EngineMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class FillTransactionExecutor {

    private static final String ORDER_FILLED = "ORDER_FILLED";

    private final JdbcTemplate jdbc;
    private final HoldingIncrementalUpdater holdingUpdater;
    private final ObjectMapper objectMapper;
    private final EngineMetrics metrics;

    public FillTransactionExecutor(
        JdbcTemplate jdbc,
        HoldingIncrementalUpdater holdingUpdater,
        @Qualifier("engineObjectMapper") ObjectMapper objectMapper,
        EngineMetrics metrics
    ) {
        this.jdbc = jdbc;
        this.holdingUpdater = holdingUpdater;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Transactional
    public void executeBatch(List<FillCommand> cmds) {
        if (cmds.isEmpty()) return;

        int[] updated = jdbc.batchUpdate(
            "UPDATE orders SET status='FILLED', filled_price=?, filled_at=? " +
                "WHERE order_id=? AND status='PENDING'",
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    FillCommand cmd = cmds.get(i);
                    ps.setBigDecimal(1, cmd.executedPrice());
                    ps.setTimestamp(2, Timestamp.valueOf(cmd.executedAt()));
                    ps.setLong(3, cmd.order().orderId());
                }

                @Override
                public int getBatchSize() {
                    return cmds.size();
                }
            }
        );

        List<FillCommand> succeeded = new ArrayList<>(cmds.size());
        for (int i = 0; i < cmds.size(); i++) {
            if (updated[i] > 0) {
                succeeded.add(cmds.get(i));
            } else {
                log.debug("fill skipped orderId={} already non-pending", cmds.get(i).order().orderId());
            }
        }
        if (succeeded.isEmpty()) return;

        jdbc.batchUpdate(
            "UPDATE wallet_balance SET locked = locked - ? WHERE wallet_id = ? AND coin_id = ?",
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    OrderDetail o = succeeded.get(i).order();
                    ps.setBigDecimal(1, o.lockedAmount());
                    ps.setLong(2, o.walletId());
                    ps.setLong(3, o.lockedCoinId());
                }

                @Override
                public int getBatchSize() {
                    return succeeded.size();
                }
            }
        );

        Timestamp createdAt = Timestamp.valueOf(LocalDateTime.now());
        jdbc.batchUpdate(
            "INSERT INTO outbox (event_type, payload, created_at) VALUES (?, ?, ?)",
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    FillCommand cmd = succeeded.get(i);
                    OrderDetail o = cmd.order();
                    OrderFilledEvent event = new OrderFilledEvent(
                        o.orderId(), o.userId(), cmd.executedPrice(), o.quantity(), cmd.executedAt()
                    );
                    String payload;
                    try {
                        payload = objectMapper.writeValueAsString(event);
                    } catch (JsonProcessingException e) {
                        throw new SQLException("outbox payload serialization failed", e);
                    }
                    ps.setString(1, ORDER_FILLED);
                    ps.setString(2, payload);
                    ps.setTimestamp(3, createdAt);
                }

                @Override
                public int getBatchSize() {
                    return succeeded.size();
                }
            }
        );

        holdingUpdater.apply(succeeded);

        metrics.matches().increment(succeeded.size());
    }
}
