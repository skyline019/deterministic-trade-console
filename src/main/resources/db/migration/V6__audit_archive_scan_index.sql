-- Archive-eligibility scan: archive-audit groups every chain by (aggregate_type, aggregate_id) and
-- tests MAX(occurred_at) < cutoff. uk_audit_aggregate_sequence covers the grouping but not the
-- occurred_at aggregate, so without this index the one-shot job degrades into a full scan plus a
-- temporary table as the audit log grows. The trailing sequence column keeps the index usable for
-- ordered chain reads that also bound by time.
CREATE INDEX idx_audit_aggregate_occurred
    ON audit_events (aggregate_type, aggregate_id, occurred_at, sequence);
