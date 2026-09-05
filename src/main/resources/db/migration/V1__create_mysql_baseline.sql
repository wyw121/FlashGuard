CREATE TABLE activity (
    id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    start_time DATETIME(6) NOT NULL,
    end_time DATETIME(6) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_activity_time_window CHECK (end_time > start_time),
    CONSTRAINT chk_activity_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ENDED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE activity_stock (
    activity_id BIGINT NOT NULL,
    initial_stock INT UNSIGNED NOT NULL,
    available_stock INT UNSIGNED NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (activity_id),
    CONSTRAINT fk_activity_stock_activity FOREIGN KEY (activity_id) REFERENCES activity (id),
    CONSTRAINT chk_activity_stock_bounds CHECK (available_stock <= initial_stock)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    activity_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_orders_order_no UNIQUE (order_no),
    CONSTRAINT uk_orders_request_id UNIQUE (request_id),
    CONSTRAINT uk_orders_activity_user UNIQUE (activity_id, user_id),
    CONSTRAINT fk_orders_activity FOREIGN KEY (activity_id) REFERENCES activity (id),
    CONSTRAINT chk_orders_status CHECK (status IN ('CONFIRMED', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
