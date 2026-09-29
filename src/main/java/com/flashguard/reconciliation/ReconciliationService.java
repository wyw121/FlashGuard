package com.flashguard.reconciliation;

import java.util.ArrayList;
import java.util.List;

import com.flashguard.activity.ActivityMapper;
import com.flashguard.activity.ActivityView;
import com.flashguard.order.OrderMapper;
import com.flashguard.reservation.RedisReservationStore;
import com.flashguard.reservation.StockReservationService;
import com.flashguard.shared.error.BusinessCode;
import com.flashguard.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {

    private final ActivityMapper activityMapper;
    private final OrderMapper orderMapper;
    private final StockReservationService reservationService;
    private final RedisReservationStore reservationStore;
    private final ReconciliationIssueMapper issueMapper;

    public ReconciliationService(
        ActivityMapper activityMapper,
        OrderMapper orderMapper,
        StockReservationService reservationService,
        RedisReservationStore reservationStore,
        ReconciliationIssueMapper issueMapper
    ) {
        this.activityMapper = activityMapper;
        this.orderMapper = orderMapper;
        this.reservationService = reservationService;
        this.reservationStore = reservationStore;
        this.issueMapper = issueMapper;
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ReconciliationResult reconcileActivity(long activityId) {
        if (activityId <= 0) {
            throw new BusinessException(BusinessCode.VALIDATION_ERROR);
        }
        ActivityView activity = activityMapper.findById(activityId)
            .orElseThrow(() -> new BusinessException(BusinessCode.ACTIVITY_NOT_FOUND));

        long totalReservations = reservationService.countByActivity(activityId);
        long openReservations = reservationService.countOpenReservations(activityId);
        long confirmedReservations = reservationService.countConfirmedByActivity(activityId);
        long orderCount = orderMapper.countByActivity(activityId);
        long confirmedWithoutOrder = reservationService.countConfirmedWithoutOrder(activityId);

        List<String> discrepancies = new ArrayList<>();
        Long redisAvailable = null;
        long redisPending = -1;
        boolean redisReadable = true;
        try {
            redisAvailable = reservationStore.getAvailableStock(activityId);
            redisPending = reservationStore.countPendingReservations(activityId);
        } catch (com.flashguard.reservation.ReservationStoreException failure) {
            redisReadable = false;
            discrepancies.add("Redis snapshot unavailable: " + failure.getClass().getSimpleName());
        }
        long mysqlStockDrift = activity.initialStock() - activity.availableStock() - orderCount;
        if (mysqlStockDrift != 0) {
            discrepancies.add("Mysql stock drift, initial - available - orders = " + mysqlStockDrift);
        }

        if (redisReadable && openReservations + confirmedReservations != redisPending) {
            discrepancies.add("Redis pending differs from DB open reservations: redis="
                + redisPending + ", dbOpen=" + openReservations + ", dbConfirmed=" + confirmedReservations);
        }
        if (confirmedWithoutOrder > 0) {
            discrepancies.add("Confirmed reservation without linked order: " + confirmedWithoutOrder);
        }
        if (redisReadable && redisAvailable == null) {
            discrepancies.add("Redis activity metadata not ready or not initialized");
        } else if (redisReadable) {
            long redisDrift = activity.availableStock() - openReservations - redisAvailable;
            if (redisDrift != 0 || redisAvailable < 0 || redisAvailable > activity.initialStock()) {
                discrepancies.add("Redis stock drift, dbAvailable - open - redisAvailable = " + redisDrift);
            }
        }
        discrepancies.forEach(detail -> issueMapper.insert(activityId, detail));

        return new ReconciliationResult(
            activityId,
            activity.initialStock(),
            activity.availableStock(),
            totalReservations,
            openReservations,
            confirmedReservations,
            orderCount,
            redisAvailable,
            redisPending,
            confirmedWithoutOrder,
            discrepancies,
            discrepancies.isEmpty()
        );
    }
}
