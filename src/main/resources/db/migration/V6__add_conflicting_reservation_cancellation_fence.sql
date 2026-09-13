CREATE TABLE reservation_cancellation_fence (
    reservation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    activity_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    expire_at DATETIME(6) NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (reservation_id),
    KEY ix_cancellation_fence_retry (status, updated_at),
    CONSTRAINT chk_cancellation_fence_status CHECK (status IN ('CANCEL_PENDING','CANCELLED')),
    CONSTRAINT chk_cancellation_fence_identity CHECK (activity_id > 0 AND user_id > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
