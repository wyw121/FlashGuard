-- Retain every cancelled attempt for audit while allowing the user to try a new request.
-- Nonterminal and CONFIRMED reservations still exclude a second active attempt.
ALTER TABLE stock_reservation
    ADD COLUMN active_user_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'CANCELLED' THEN NULL ELSE user_id END) STORED,
    ADD UNIQUE KEY uk_stock_reservation_active_user (activity_id, active_user_id);

ALTER TABLE stock_reservation DROP INDEX uk_stock_reservation_activity_user;
