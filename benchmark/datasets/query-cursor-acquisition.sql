SELECT id,created_at
FROM historical_orders FORCE INDEX(ix_history_status_created)
WHERE status='CONFIRMED'
ORDER BY created_at DESC,id DESC LIMIT 1 OFFSET 799999;
