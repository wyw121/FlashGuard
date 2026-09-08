ALTER TABLE orders
    ADD COLUMN reservation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;

CREATE UNIQUE INDEX uk_orders_reservation_id
    ON orders (reservation_id);
