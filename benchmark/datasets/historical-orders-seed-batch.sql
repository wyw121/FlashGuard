-- Execute ten times, setting @batch to 0..9. Each statement inserts exactly 100000 deterministic rows.
INSERT INTO historical_orders(id,order_no,request_id,activity_id,user_id,reservation_id,status,created_at,updated_at)
SELECT n+1,
       CONCAT('H',LPAD(n+1,12,'0')),
       CONCAT('history-request-',LPAD(n+1,7,'0')),
       1+FLOOR(n/10000),
       1+MOD(n,10000),
       CONCAT('history-reservation-',LPAD(n+1,7,'0')),
       IF(MOD(n,10)=0,'CANCELLED','CONFIRMED'),
       TIMESTAMPADD(SECOND,FLOOR(n/10),'2020-01-01 00:00:00'),
       TIMESTAMPADD(SECOND,FLOOR(n/10),'2020-01-01 00:00:00')
FROM (
    SELECT @batch*100000+d0.n+10*d1.n+100*d2.n+1000*d3.n+10000*d4.n AS n
    FROM seed_digits d0 CROSS JOIN seed_digits d1 CROSS JOIN seed_digits d2
    CROSS JOIN seed_digits d3 CROSS JOIN seed_digits d4
) deterministic_rows
ORDER BY n;
