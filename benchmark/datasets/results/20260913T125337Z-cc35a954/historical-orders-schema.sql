-- Experiment-only table. Run exclusively in a newly created disposable database.
-- Mirrors historical order identifiers/business uniqueness; omits activity FK because no live activity workload is seeded.
CREATE TABLE historical_orders (
    id BIGINT NOT NULL,
    order_no VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    activity_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reservation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_history_order_no (order_no),
    UNIQUE KEY uk_history_request_id (request_id),
    UNIQUE KEY uk_history_reservation_id (reservation_id),
    UNIQUE KEY uk_history_activity_user (activity_id,user_id),
    CHECK (status IN ('CONFIRMED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE seed_digits (n TINYINT UNSIGNED NOT NULL PRIMARY KEY);
INSERT INTO seed_digits(n) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
