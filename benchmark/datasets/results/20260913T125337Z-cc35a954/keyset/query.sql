SELECT id,activity_id,user_id,status,created_at
FROM historical_orders FORCE INDEX(ix_history_status_created)
WHERE status='CONFIRMED'
  AND (created_at < TIMESTAMP '2020-01-01 03:05:11'
       OR (created_at = TIMESTAMP '2020-01-01 03:05:11' AND id < 111113))
ORDER BY created_at DESC,id DESC LIMIT 50;
