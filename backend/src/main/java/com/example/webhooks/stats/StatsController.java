package com.example.webhooks.stats;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    public record Stats(int windowHours, long total, Map<String, Long> byStatus,
                        Double successRate, Long p95LatencyMs, long dlqTotal) {}

    private final NamedParameterJdbcTemplate jdbc;

    public StatsController(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Stats stats(@RequestParam(defaultValue = "24") int hours) {
        int window = Math.min(Math.max(hours, 1), 720);
        var p = new MapSqlParameterSource("since", OffsetDateTime.now(ZoneOffset.UTC).minusHours(window));

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String s : new String[]{"PENDING", "IN_FLIGHT", "RETRYING", "SUCCESS", "DLQ"}) byStatus.put(s, 0L);
        jdbc.query("select status, count(*) as c from deliveries where created_at >= :since group by status", p,
                (RowCallbackHandler) rs -> byStatus.put(rs.getString("status"), rs.getLong("c")));

        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long ok = byStatus.get("SUCCESS"), dead = byStatus.get("DLQ");
        Double successRate = (ok + dead) == 0 ? null : (double) ok / (ok + dead);   // finished deliveries only

        Double p95 = jdbc.queryForObject("""
                select percentile_cont(0.95) within group (order by latency_ms)
                from deliveries
                where created_at >= :since and status = 'SUCCESS' and latency_ms is not null
                """, p, Double.class);

        Long dlqTotal = jdbc.queryForObject("select count(*) from deliveries where status = 'DLQ'",
                new MapSqlParameterSource(), Long.class);   // all time: DLQ rows need action no matter how old

        return new Stats(window, total, byStatus, successRate,
                p95 == null ? null : Math.round(p95), dlqTotal == null ? 0 : dlqTotal);
    }
}