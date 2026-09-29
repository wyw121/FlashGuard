CREATE TABLE historical_orders (
    id BIGINT NOT NULL,
    order_no VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    activity_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reservation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_history_order_no (order_no),
    UNIQUE KEY uk_history_request_id (request_id),
    UNIQUE KEY uk_history_reservation_id (reservation_id),
    UNIQUE KEY uk_history_activity_user (activity_id,user_id),
    CHECK (status IN ('CONFIRMED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE seed_digits (n TINYINT UNSIGNED NOT NULL PRIMARY KEY);
INSERT INTO seed_digits(n) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
SET @batch = 0;
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
SET @batch = 1;
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
SET @batch = 2;
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
SET @batch = 3;
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
SET @batch = 4;
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
SET @batch = 5;
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
SET @batch = 6;
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
SET @batch = 7;
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
SET @batch = 8;
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
SET @batch = 9;
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
ANALYZE TABLE historical_orders;
CREATE INDEX ix_history_user_status_created ON historical_orders(user_id,status,created_at,id,activity_id);
CREATE INDEX ix_history_activity_status_created ON historical_orders(activity_id,status,created_at,id,user_id);
ANALYZE TABLE historical_orders;
CREATE INDEX ix_history_status_created ON historical_orders(status,created_at,id,activity_id,user_id);
ANALYZE TABLE historical_orders;
