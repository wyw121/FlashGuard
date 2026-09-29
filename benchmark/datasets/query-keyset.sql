SELECT id,activity_id,user_id,status,created_at
FROM historical_orders FORCE INDEX(ix_history_status_created)
WHERE status='CONFIRMED'
  AND (created_at < TIMESTAMP '{{cursor_created_at}}'
       OR (created_at = TIMESTAMP '{{cursor_created_at}}' AND id < {{cursor_id}}))
ORDER BY created_at DESC,id DESC LIMIT 50;
