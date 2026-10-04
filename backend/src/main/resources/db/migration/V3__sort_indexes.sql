-- One index per sortable column, ending in id so keyset pagination can seek instead of scan.
-- B-tree indexes can be read backwards, so each one serves both asc and desc.
create index idx_deliveries_attempts on deliveries (attempt_count desc, id desc);
create index idx_deliveries_latency  on deliveries ((coalesce(latency_ms, -1)) desc, id desc);