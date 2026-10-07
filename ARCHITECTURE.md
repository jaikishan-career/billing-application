# Architecture

## Modules and storage

`api` contains HTTP controllers, pagination, and error mapping. `validation` owns field checks and typed flight/hotel/rail/Navan-fee metadata records. `service` owns duplicate identity, lifecycle rules, database transaction boundaries, snapshots, and decimal rollups. `persistence` contains SQL repositories, child-line storage, and JSON decoding. Controllers do not perform SQL; repositories do not decide HTTP behavior. Flyway versioned migrations define the schema.

PostgreSQL stores `transactions` with server UUID primary key, unique client `external_id`, type, currency, timestamp, gross `DECIMAL(19,2)` total, and JSONB metadata. `transaction_tax_lines` and `transaction_fee_lines` are ordered child tables with foreign keys and cascade deletion. Taxes carry optional `DECIMAL(12,6)` rates; fees are separate. Position constraints preserve lists, including identical repeated charge entries.

Type-specific JSONB preserves the requested shape and optional extensions: flight OD/fare and coupon flight numbers/times; hotel name/address/stay/rate/extras; rail legs with stations/times/train/class; and Navan trip/agent-call fee details. Java records validate each structure before writes. JSONB rather than a text blob allows future SQL metadata queries/indexes without a sprawling nullable table or many joins. No metadata search endpoint is needed for this task, so speculative JSON indexes are omitted.

## Identity and concurrent writes

The database unique index on `external_id` is authoritative. A preliminary lookup allows the intentionally incomplete duplicate fixture to return 409 with the existing ID. The unique constraint resolves concurrent valid inserts; a losing transaction rolls back completely before a fresh lookup returns the winner's ID. Header, metadata, taxes, and fees commit together.

Transaction PATCH/DELETE acquire `SELECT ... FOR UPDATE` on the source row, then check indexed invoice membership under PostgreSQL READ COMMITTED. All invoice writes also lock their affected source rows in sorted ID order. Invoice PATCH/DELETE lock the invoice header first; PATCH locks the union of old and new sources before replacing snapshots. These rules serialize overlapping writes and prevent a source update from passing a billed-status check concurrently with invoice creation. Last-write-wins is allowed, but child lists cannot tear. Lock timeouts return retryable 409. No process-memory locks or auxiliary coordination services exist.

## Frozen invoices

`invoices` holds currency, total, and creation time. `invoice_lines` has one row per included source, plus copied external ID/type/currency/time/total/JSONB metadata. `invoice_tax_lines` and `invoice_fee_lines` copy each snapshot's charges. `UNIQUE(invoice_id, transaction_id)` forbids duplicate inclusion; the source FK restricts deletion while referenced. Invoice responses read only these copied values, never live transaction joins. Rollups are derived from frozen charges: taxes by exact name and numerical rate (null separate), fees by exact name. Gross totals sum once using BigDecimal. Invoice creation/replacement validates all IDs/currencies and commits header and snapshots atomically; deletion cascades snapshots but preserves sources. Full GETs use REPEATABLE READ so multiple SQL reads see one consistent version.

API edits/deletes to any billed source return 409. The snapshot integration test deliberately changes the source through test-only SQL to independently prove the invoice is frozen; a separate API test proves public edits are blocked. Removing the final invoice membership permits source edits again.

## High volume

Primary keys index resource GETs. A unique external-ID index handles duplicate traffic. `invoice_lines(transaction_id)` indexes billed-status checks; composite unique indexes beginning with parent IDs serve child retrieval. Lists use SQL LIMIT/OFFSET with default 50/max 100 and return summaries, avoiding child hydration and unbounded table scans in application memory. Invoice requests are capped at 100 sources and charge lists at 1000 entries.

The first bottlenecks are lock contention on popular bookings during duplicate POST/PATCH/invoice writes, database connections (pool 10), and deep OFFSET scans. Multi-statement source/snapshot hydration adds bounded query overhead for large trips. Sorted locks reduce deadlocks; short atomic transactions and batch charge inserts limit lock duration. At greater scale use cursor pagination on indexed IDs, batch-load children, tune the connection pool against database capacity, and inspect actual query plans. A single PostgreSQL instance remains the throughput boundary. Redis/Kafka, monitoring infrastructure, and distributed lock managers are unnecessary for this scope.
