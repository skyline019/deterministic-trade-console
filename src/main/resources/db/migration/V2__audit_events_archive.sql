-- Cold storage for quiet aggregate chains. Live audit_events stays append-only for active aggregates.
CREATE TABLE audit_events_archive (
    id CHAR(36) NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(16) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    sequence BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    actor_name VARCHAR(32) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    previous_hash CHAR(64) NOT NULL,
    event_hash CHAR(64) NOT NULL,
    archived_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_audit_archive_aggregate_sequence UNIQUE (aggregate_type, aggregate_id, sequence),
    CONSTRAINT chk_audit_archive_sequence CHECK (sequence > 0),
    INDEX idx_audit_archive_time (occurred_at),
    INDEX idx_audit_archive_archived (archived_at)
) ENGINE = InnoDB;
