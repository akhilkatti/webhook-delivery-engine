create table subscribers (
                             id                  uuid primary key default gen_random_uuid(),
                             name                text not null,
                             url                 text not null,
                             secret              text not null,
                             rate_limit_per_min  int  not null default 60,
                             active              boolean not null default true,
                             created_at          timestamptz not null default now()
);

create table events (
                        id               uuid primary key default gen_random_uuid(),
                        type             text  not null,
                        payload          jsonb not null,
                        idempotency_key  text unique,
                        created_at       timestamptz not null default now()
);

create table deliveries (
                            id               uuid primary key default gen_random_uuid(),
                            event_id         uuid not null references events(id),
                            subscriber_id    uuid not null references subscribers(id),
                            status           text not null,   -- PENDING, IN_FLIGHT, SUCCESS, RETRYING, DLQ
                            attempt_count    int  not null default 0,
                            next_attempt_at  timestamptz,
                            last_http_status int,
                            last_error       text,
                            latency_ms       int,
                            created_at       timestamptz not null default now(),
                            updated_at       timestamptz not null default now()
);

-- Keyset pagination + filters
create index idx_deliveries_created        on deliveries (created_at desc, id desc);
create index idx_deliveries_status_created on deliveries (status, created_at desc, id desc);
create index idx_deliveries_sub_created    on deliveries (subscriber_id, created_at desc, id desc);

-- Reaper: find stuck IN_FLIGHT rows quickly
create index idx_deliveries_inflight on deliveries (updated_at) where status = 'IN_FLIGHT';