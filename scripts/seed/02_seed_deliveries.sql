with ev as (select array_agg(id) as a from events where idempotency_key like 'seed-%'),
     sb as (select array_agg(id) as a from subscribers where name like 'seed-%'),
     base as (
         select random() as r, now() - random() * interval '90 days' as ts
from generate_series(1, 250000)                      -- chunk size
    ),
    t as (
select ts,
    case when r < 0.85 then 'SUCCESS' when r < 0.93 then 'DLQ'
    when r < 0.98 then 'RETRYING' else 'PENDING' end as status,
    case when r < 0.85 then 1 + (random() < 0.15)::int
    when r < 0.93 then 8
    when r < 0.98 then 1 + floor(random() * 5)::int
    else 0 end as attempts
from base
    )
insert into deliveries
(event_id, subscriber_id, status, attempt_count, next_attempt_at,
 last_http_status, last_error, latency_ms, created_at, updated_at)
select
    ev.a[1 + floor(random() * cardinality(ev.a))::int],
    sb.a[1 + floor(random() * cardinality(sb.a))::int],
    t.status,
    t.attempts,
    null,                                                      -- never "due": the recovery job ignores NULL
    case t.status when 'SUCCESS'  then 200
                  when 'DLQ'      then (array[500, 502, 503, 429])[1 + floor(random() * 4)::int]
                when 'RETRYING' then (array[500, 503])[1 + floor(random() * 2)::int]
end,
  case t.status when 'DLQ' then 'max attempts reached: HTTP 5xx'
                when 'RETRYING' then 'HTTP 5xx' end,
  case when t.status <> 'PENDING' then 20 + floor(random() * random() * 3000)::int end,
  t.ts,
  t.ts + interval '2 seconds'
from t, ev, sb;