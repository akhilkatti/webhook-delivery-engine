-- Speeds up the orphan-recovery query (only rows still waiting to be delivered)
create index idx_deliveries_due on deliveries (next_attempt_at)
    where status in ('PENDING', 'RETRYING');