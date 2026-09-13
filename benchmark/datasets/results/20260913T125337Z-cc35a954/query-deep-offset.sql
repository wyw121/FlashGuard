SELECT id,activity_id,user_id,status,created_at
FROM historical_orders FORCE INDEX(ix_history_status_created)
WHERE status='CONFIRMED'
ORDER BY created_at DESC,id DESC LIMIT 50 OFFSET 800000;
