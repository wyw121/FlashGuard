-- Added only after baseline plans/timings have been recorded. Never a production migration.
CREATE INDEX ix_history_user_status_created ON historical_orders(user_id,status,created_at,id,activity_id);
CREATE INDEX ix_history_activity_status_created ON historical_orders(activity_id,status,created_at,id,user_id);
