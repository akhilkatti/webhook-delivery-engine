package com.example.webhooks.delivery;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class DeliveryQueryService {

    public record Row(UUID id, UUID eventId, UUID subscriberId, String subscriberName, String status,
                      int attemptCount, Instant nextAttemptAt, Integer lastHttpStatus, String lastError,
                      Integer latencyMs, Instant createdAt, Instant updatedAt) {}

    public record Page(List<Row> items, String nextCursor, boolean hasMore) {}

    public record Detail(Row delivery, String eventType, String payload) {}

    /** Whitelist: the SQL fragment comes from this enum, never from user input (no SQL injection). */
    private enum SortField {
        CREATED_AT("createdat", "d.created_at"),
        ATTEMPT_COUNT("attemptcount", "d.attempt_count"),
        LATENCY_MS("latencyms", "coalesce(d.latency_ms, -1)");

        final String param;
        final String expr;
        SortField(String param, String expr) { this.param = param; this.expr = expr; }

        static SortField parse(String s) {
            for (SortField f : values()) if (f.param.equalsIgnoreCase(s)) return f;
            throw bad("sort must be one of: createdAt, attemptCount, latencyMs");
        }
    }

    private static final String SELECT = """
            select d.id, d.event_id, d.subscriber_id, s.name as subscriber_name, d.status, d.attempt_count,
                   d.next_attempt_at, d.last_http_status, d.last_error, d.latency_ms, d.created_at, d.updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DeliveryQueryService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Page list(List<String> statuses, UUID subscriberId, Integer httpStatus, Instant from, Instant to,
                     String sortParam, String dirParam, String cursor, int limitParam) {
        SortField sort = SortField.parse(sortParam);
        boolean desc = parseDesc(dirParam);
        int limit = Math.min(Math.max(limitParam, 1), 200);

        StringBuilder sql = new StringBuilder(SELECT)
                .append(" from deliveries d join subscribers s on s.id = d.subscriber_id where 1 = 1");
        MapSqlParameterSource p = new MapSqlParameterSource();

        if (statuses != null && !statuses.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String s : statuses) {
                try { names.add(DeliveryStatus.valueOf(s.trim().toUpperCase()).name()); }
                catch (IllegalArgumentException e) { throw bad("unknown status: " + s); }
            }
            sql.append(" and d.status in (:statuses)");
            p.addValue("statuses", names);
        }
        if (subscriberId != null) { sql.append(" and d.subscriber_id = :sub"); p.addValue("sub", subscriberId); }
        if (httpStatus != null)   { sql.append(" and d.last_http_status = :hs"); p.addValue("hs", httpStatus); }
        if (from != null) { sql.append(" and d.created_at >= :from"); p.addValue("from", utc(from)); }
        if (to != null)   { sql.append(" and d.created_at < :to");    p.addValue("to", utc(to)); }

        if (cursor != null && !cursor.isBlank()) {
            Cursor c = Cursor.decode(cursor);
            if (c.sort != sort || c.desc != desc) throw bad("cursor does not match the sort/dir of this request");
            // Seek past the last row seen: (sortValue, id) compared as a pair.
            sql.append(" and (").append(sort.expr).append(", d.id) ").append(desc ? "<" : ">")
                    .append(" (:cv, :cid)");
            p.addValue("cv", sort == SortField.CREATED_AT
                    ? utc(Instant.EPOCH.plus(c.value, ChronoUnit.MICROS))
                    : (Object) (int) c.value);
            p.addValue("cid", c.id);
        }

        String dir = desc ? "desc" : "asc";
        sql.append(" order by ").append(sort.expr).append(' ').append(dir).append(", d.id ").append(dir)
                .append(" limit :lim");
        p.addValue("lim", limit + 1);                      // one extra row tells us if there is a next page

        List<Row> rows = jdbc.query(sql.toString(), p, DeliveryQueryService::mapRow);
        boolean hasMore = rows.size() > limit;
        List<Row> items = hasMore ? new ArrayList<>(rows.subList(0, limit)) : rows;
        String next = hasMore ? new Cursor(sort, desc, sortValue(sort, items.get(items.size() - 1)),
                items.get(items.size() - 1).id()).encode() : null;
        return new Page(items, next, hasMore);
    }

    public Detail get(UUID id) {
        String sql = SELECT + ", e.type as event_type, e.payload::text as payload "
                + "from deliveries d join subscribers s on s.id = d.subscriber_id "
                + "join events e on e.id = d.event_id where d.id = :id";
        List<Detail> found = jdbc.query(sql, new MapSqlParameterSource("id", id),
                (rs, i) -> new Detail(mapRow(rs, i), rs.getString("event_type"), rs.getString("payload")));
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery not found");
        return found.get(0);
    }

    // ---- helpers ----

    private static Row mapRow(ResultSet rs, int i) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getObject("subscriber_id", UUID.class), rs.getString("subscriber_name"),
                rs.getString("status"), rs.getInt("attempt_count"),
                instant(rs, "next_attempt_at"), rs.getObject("last_http_status", Integer.class),
                rs.getString("last_error"), rs.getObject("latency_ms", Integer.class),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime utc(Instant i) { return OffsetDateTime.ofInstant(i, ZoneOffset.UTC); }

    private static long sortValue(SortField f, Row r) {
        return switch (f) {
            case CREATED_AT -> ChronoUnit.MICROS.between(Instant.EPOCH, r.createdAt());   // Postgres keeps microseconds
            case ATTEMPT_COUNT -> r.attemptCount();
            case LATENCY_MS -> r.latencyMs() == null ? -1 : r.latencyMs();
        };
    }

    private static boolean parseDesc(String dir) {
        if ("desc".equalsIgnoreCase(dir)) return true;
        if ("asc".equalsIgnoreCase(dir)) return false;
        throw bad("dir must be asc or desc");
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    /** Opaque cursor: base64url("sort|dir|value|id"). Clients treat it as a black box. */
    private record Cursor(SortField sort, boolean desc, long value, UUID id) {
        String encode() {
            String raw = sort.param + "|" + (desc ? "desc" : "asc") + "|" + value + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String s) {
            try {
                String[] p = new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8).split("\\|", 4);
                return new Cursor(SortField.parse(p[0]), parseDesc(p[1]), Long.parseLong(p[2]), UUID.fromString(p[3]));
            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                throw bad("invalid cursor");
            }
        }
    }
}