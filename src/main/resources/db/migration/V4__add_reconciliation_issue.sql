CREATE TABLE reconciliation_issue (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    activity_id BIGINT NOT NULL,
    issue_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    details TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    observations BIGINT NOT NULL DEFAULT 1,
    first_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_reconciliation_issue (activity_id, issue_key),
    INDEX idx_reconciliation_issue_status (status, last_seen_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_reservation_expiry ON stock_reservation(status, expire_at, updated_at);
