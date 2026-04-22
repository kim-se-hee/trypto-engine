package ksh.tryptoengine.engine;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeCoinResolver {

    private final JdbcTemplate jdbc;
    private final Map<String, Long> cache = new HashMap<>();

    @PostConstruct
    void preload() {
        jdbc.query(
            "SELECT ec.exchange_coin_id, em.name AS exchange_name, ec.display_name " +
                "FROM exchange_coin ec " +
                "JOIN exchange_market em ON em.exchange_id = ec.exchange_id",
            rs -> {
                cache.put(key(rs.getString("exchange_name"), rs.getString("display_name")),
                    rs.getLong("exchange_coin_id"));
            }
        );
        log.info("ExchangeCoinResolver loaded {} mappings", cache.size());
    }

    public Long resolve(String exchange, String displayName) {
        Long hit = cache.get(key(exchange, displayName));
        if (hit != null) return hit;
        return lazyLoad(exchange, displayName);
    }

    private Long lazyLoad(String exchange, String displayName) {
        try {
            Long id = jdbc.queryForObject(
                "SELECT ec.exchange_coin_id FROM exchange_coin ec " +
                    "JOIN exchange_market em ON em.exchange_id = ec.exchange_id " +
                    "WHERE em.name=? AND ec.display_name=?",
                Long.class, exchange, displayName
            );
            cache.put(key(exchange, displayName), id);
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    private String key(String exchange, String displayName) {
        return exchange + ":" + displayName;
    }
}
