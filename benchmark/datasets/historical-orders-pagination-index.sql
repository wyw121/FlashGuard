-- Same index forced for both pagination strategies to isolate pagination behavior.
CREATE INDEX ix_history_status_created ON historical_orders(status,created_at,id,activity_id,user_id);
