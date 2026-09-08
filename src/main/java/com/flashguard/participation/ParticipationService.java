package com.flashguard.participation;

import com.flashguard.order.OrderMapper;
import com.flashguard.order.OrderRecord;
import com.flashguard.reservation.PendingReservation;
import com.flashguard.reservation.ReservationResult;
import com.flashguard.reservation.RedisReservationStore;
import com.flashguard.reservation.StockReservationRecord;
import com.flashguard.reservation.StockReservationService;
import com.flashguard.reservation.ReservationStoreException;
import com.flashguard.shared.error.BusinessCode;
import com.flashguard.shared.error.BusinessException;
import com.flashguard.shared.observability.CorrelationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ParticipationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParticipationService.class);

    private final ParticipationTransaction transaction;
    private final OrderMapper orderMapper;
    private final RedisReservationStore reservationStore;
    private final StockReservationService stockReservationService;

    public ParticipationService(
        ParticipationTransaction transaction,
        OrderMapper orderMapper,
        RedisReservationStore reservationStore,
        StockReservationService stockReservationService
    ) {
        this.transaction = transaction;
        this.orderMapper = orderMapper;
        this.reservationStore = reservationStore;
        this.stockReservationService = stockReservationService;
    }

    public ParticipationOutcome participate(ParticipationCommand command) {
        CorrelationContext.setRequestId(command.requestId());
        LOGGER.info("Handling participation request for activity={} user={}", command.activityId(), command.userId());
        OrderRecord replay = orderMapper.findByRequestId(command.requestId()).orElse(null);
        if (replay != null) {
            return ParticipationOutcome.replay(replay);
        }

        ReservationResult reservationResult;
        try {
            reservationResult = reservationStore.reserve(
                command.activityId(),
                command.userId(),
                command.requestId()
            );
        } catch (ReservationStoreException exception) {
            return executeLegacyPath(command);
        }

        switch (reservationResult.decision()) {
            case SUCCESS, REPLAY -> {
                return reserveAsPending(command.requestId(), reservationResult.reservation());
            }
            case DUPLICATE, REQUEST_CONFLICT -> {
                throw new BusinessException(BusinessCode.DUPLICATE_PURCHASE);
            }
            case SOLD_OUT -> {
                throw new BusinessException(BusinessCode.SOLD_OUT);
            }
            case NOT_STARTED -> {
                throw new BusinessException(BusinessCode.ACTIVITY_NOT_STARTED);
            }
            case ENDED -> {
                throw new BusinessException(BusinessCode.ACTIVITY_ENDED);
            }
            case NOT_READY -> {
                return executeLegacyPath(command);
            }
            default -> {
                throw new BusinessException(BusinessCode.INTERNAL_ERROR);
            }
        } finally {
            CorrelationContext.clearBusinessContext();
        }
    }

    private ParticipationOutcome reserveAsPending(
        String requestId,
        PendingReservation pendingReservation
    ) {
        StockReservationRecord reservation = stockReservationService.findByRequestId(requestId)
            .orElseGet(() -> {
                try {
                    return stockReservationService.createPending(pendingReservation);
                } catch (DuplicateKeyException exception) {
                    return stockReservationService.findByRequestId(requestId)
                        .orElseThrow(() -> exception);
                }
            });
        CorrelationContext.setReservationId(reservation.reservationId());
        LOGGER.info("Created/returned reservation for requestId={} reservationId={} status={}",
            requestId,
            reservation.reservationId(),
            reservation.status());
        return ParticipationOutcome.pendingForRequestIdWithStatus(
            requestId,
            reservation.status().name()
        );
    }

    private ParticipationOutcome executeLegacyPath(ParticipationCommand command) {
        try {
            return transaction.execute(command);
        } catch (DuplicateKeyException duplicate) {
            OrderRecord replay = orderMapper.findByRequestId(command.requestId()).orElse(null);
            if (replay != null) {
                return ParticipationOutcome.replay(replay);
            }
            if (orderMapper.findByActivityAndUser(command.activityId(), command.userId()).isPresent()) {
                throw new BusinessException(BusinessCode.DUPLICATE_PURCHASE);
            }
            throw duplicate;
        }
    }

    public ParticipationOutcome findByRequestId(String requestId) {
        CorrelationContext.setRequestId(requestId);
        try {
            return orderMapper.findByRequestId(requestId)
                .map(ParticipationOutcome::success)
                .orElseGet(() -> stockReservationService.findByRequestId(requestId)
                    .map(record -> ParticipationOutcome.pendingForRequestIdWithStatus(
                        requestId,
                        record.status().name()
                    ))
                    .orElseThrow(() -> new BusinessException(BusinessCode.PARTICIPATION_NOT_FOUND))
                );
        } finally {
            CorrelationContext.clearBusinessContext();
        }
    }
}
