-- 10 inactive subscribers: ingestion never fans out to them, and workers skip them
insert into subscribers (name, url, secret, rate_limit_per_min, active)
select 'seed-' || g, 'http://localhost:8080/mock/seed-' || g, 'whsec_seed_' || g, 60, false
from generate_series(1, 10) g;

-- 1,000 events that the seeded deliveries will point at (the unique key makes a second run fail safely)
insert into events (type, payload, idempotency_key)
select 'order.created',
       jsonb_build_object('orderId', g, 'amount', round((random() * 500)::numeric, 2), 'currency', 'INR'),
       'seed-' || g
from generate_series(1, 1000) g;