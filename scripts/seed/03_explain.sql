-- C. first page, default sort
explain (analyze, buffers)
select d.*, s.name from deliveries d join subscribers s on s.id = d.subscriber_id
order by d.created_at desc, d.id desc limit 51;

-- D. single status
explain (analyze, buffers)
select d.*, s.name from deliveries d join subscribers s on s.id = d.subscriber_id
where d.status = 'DLQ'
order by d.created_at desc, d.id desc limit 51;

-- E. two statuses, deep page
explain (analyze, buffers)
select d.*, s.name from deliveries d join subscribers s on s.id = d.subscriber_id
where d.status in ('DLQ', 'RETRYING')
  and (d.created_at, d.id) < (now() - interval '45 days', 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)
order by d.created_at desc, d.id desc limit 51;

-- F. HTTP status filter (no dedicated index yet)
explain (analyze, buffers)
select d.*, s.name from deliveries d join subscribers s on s.id = d.subscriber_id
where d.last_http_status = 500
order by d.created_at desc, d.id desc limit 51;

-- F2. an HTTP status that matches nothing (worst case for a filter without an index)
explain (analyze, buffers)
select d.*, s.name from deliveries d join subscribers s on s.id = d.subscriber_id
where d.last_http_status = 418
order by d.created_at desc, d.id desc limit 51;

-- G. status filter + sort by a different column
explain (analyze, buffers)
select d.*, s.name from deliveries d join subscribers s on s.id = d.subscriber_id
where d.status = 'SUCCESS'
order by d.attempt_count desc, d.id desc limit 51;

-- H. sort by latency (the expression must match the index)
explain (analyze, buffers)
select d.*, s.name from deliveries d join subscribers s on s.id = d.subscriber_id
order by coalesce(d.latency_ms, -1) desc, d.id desc limit 51;

-- I. the dashboard, 7-day window
explain (analyze, buffers)
select status, count(*) from deliveries where created_at >= now() - interval '168 hours' group by status;

explain (analyze, buffers)
select percentile_cont(0.95) within group (order by latency_ms) from deliveries
where created_at >= now() - interval '168 hours' and status = 'SUCCESS' and latency_ms is not null;

explain (analyze, buffers)
select count(*) from deliveries where status = 'DLQ';