CREATE TABLE stock_reservation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    activity_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expire_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_stock_reservation_reservation_id UNIQUE (reservation_id),
    CONSTRAINT uk_stock_reservation_request_id UNIQUE (request_id),
    CONSTRAINT uk_stock_reservation_activity_user UNIQUE (activity_id, user_id),
    CONSTRAINT fk_stock_reservation_activity FOREIGN KEY (activity_id) REFERENCES activity (id),
    CONSTRAINT chk_stock_reservation_status CHECK (
        status IN ('PENDING', 'MQ_COMMITTED', 'CONFIRMED', 'CANCEL_PENDING', 'CANCELLED', 'COMPENSATION_FAILED')
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

