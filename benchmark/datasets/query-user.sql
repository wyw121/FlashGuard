SELECT id,activity_id,user_id,status,created_at
FROM historical_orders
WHERE user_id=4242 AND status='CONFIRMED'
ORDER BY created_at DESC,id DESC LIMIT 50;
