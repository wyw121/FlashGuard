package com.flashguard.participation;

import com.flashguard.mq.ReservationMessagePublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.flashguard.order.OrderMapper;
import com.flashguard.order.OrderRecord;
import com.flashguard.order.OrderStatus;
import com.flashguard.reservation.PendingReservation;
import com.flashguard.reservation.ReservationResult;
import com.flashguard.reservation.RedisReservationStore;
import com.flashguard.reservation.StockReservationRecord;
import com.flashguard.reservation.StockReservationService;
import com.flashguard.reservation.StockReservationStatus;
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
    private final ParticipationRateLimiter rateLimiter;
    private final ReservationMessagePublisher publisher;
    private final String mode;

    public ParticipationService(ParticipationTransaction transaction, OrderMapper orderMapper,
        RedisReservationStore reservationStore, StockReservationService stockReservationService,
        ParticipationRateLimiter rateLimiter) {
        this(transaction, orderMapper, reservationStore, stockReservationService, rateLimiter,
            (ReservationMessagePublisher) null, "mysql");
    }

    @Autowired
    public ParticipationService(ParticipationTransaction transaction, OrderMapper orderMapper,
        RedisReservationStore reservationStore, StockReservationService stockReservationService,
        ParticipationRateLimiter rateLimiter, ObjectProvider<ReservationMessagePublisher> publisher,
        @Value("${flashguard.participation.mode:mysql}") String mode) {
        this(transaction,orderMapper,reservationStore,stockReservationService,rateLimiter,publisher.getIfAvailable(),mode);
    }

    public ParticipationService(ParticipationTransaction transaction, OrderMapper orderMapper,
        RedisReservationStore reservationStore, StockReservationService stockReservationService,
        ParticipationRateLimiter rateLimiter, ReservationMessagePublisher publisher, String mode) {
        if (!"mysql".equals(mode) && !"async".equals(mode)) throw new IllegalArgumentException("Participation mode must be mysql or async");
        this.transaction = transaction;
        this.orderMapper = orderMapper;
        this.reservationStore = reservationStore;
        this.stockReservationService = stockReservationService;
        this.rateLimiter = rateLimiter;
        this.publisher = publisher;
        this.mode = mode;
    }
    public ParticipationOutcome participate(ParticipationCommand command) {
        try {
            return participateInternal(command);
        } finally {
            CorrelationContext.clearBusinessContext();
        }
    }

    private ParticipationOutcome participateInternal(ParticipationCommand command) {
        rateLimiter.ensureAllowed(command.activityId(), command.userId());
        CorrelationContext.setRequestId(command.requestId());
        LOGGER.info("Handling participation request for activity={} user={}", command.activityId(), command.userId());
        OrderRecord replay = orderMapper.findByRequestId(command.requestId()).orElse(null);
        if (replay != null) {
            return replayForCommand(command, replay);
        }

        if ("mysql".equals(mode)) return executeLegacyPath(command);
        if (publisher == null) throw new BusinessException(BusinessCode.SERVICE_UNAVAILABLE);

        stockReservationService.findByRequestId(command.requestId()).ifPresent(existing -> {
            ensureRequestIdentity(command, existing.activityId(), existing.userId());
        });

        ReservationResult reservationResult;
        try {
            reservationResult = reservationStore.reserve(
                command.activityId(),
                command.userId(),
                command.requestId()
            );
        } catch (ReservationStoreException exception) {
            throw new BusinessException(BusinessCode.SERVICE_UNAVAILABLE);
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
                throw new BusinessException(BusinessCode.SERVICE_UNAVAILABLE);
            }
            default -> {
                throw new BusinessException(BusinessCode.INTERNAL_ERROR);
            }
        }
    }

    private ParticipationOutcome reserveAsPending(
        String requestId,
        PendingReservation pendingReservation
    ) {
        StockReservationRecord reservation = publisher.publish(pendingReservation);
        CorrelationContext.setReservationId(reservation.reservationId());
        LOGGER.info("Created/returned reservation for requestId={} reservationId={} status={}",
            requestId,
            reservation.reservationId(),
            reservation.status());
        return outcomeForReservation(reservation);
    }

    private ParticipationOutcome outcomeForReservation(StockReservationRecord reservation) {
        if (reservation.status() != StockReservationStatus.CONFIRMED) {
            return ParticipationOutcome.pendingForRequestIdWithStatus(
                reservation.requestId(), reservation.status().name());
        }
        // The consumer can commit after the initial order lookup but before this reservation read.
        // CONFIRMED is only a complete response when its committed order is also returned.
        OrderRecord order = orderMapper.findByRequestId(reservation.requestId())
            .filter(candidate -> candidate.requestId().equals(reservation.requestId())
                && candidate.activityId() == reservation.activityId()
                && candidate.userId() == reservation.userId()
                && candidate.status() == OrderStatus.CONFIRMED
                && candidate.orderNo() != null && !candidate.orderNo().isBlank())
            .orElseThrow(() -> new BusinessException(BusinessCode.SERVICE_UNAVAILABLE));
        return ParticipationOutcome.success(order);
    }

    private ParticipationOutcome replayForCommand(ParticipationCommand command, OrderRecord order) {
        ensureRequestIdentity(command, order.activityId(), order.userId());
        return ParticipationOutcome.replay(order);
    }

    private void ensureRequestIdentity(ParticipationCommand command, long activityId, long userId) {
        if (command.activityId() != activityId || command.userId() != userId) {
            throw new BusinessException(BusinessCode.DUPLICATE_PURCHASE);
        }
    }

    private ParticipationOutcome executeLegacyPath(ParticipationCommand command) {
        try {
            return transaction.execute(command);
        } catch (DuplicateKeyException duplicate) {
            OrderRecord replay = orderMapper.findByRequestId(command.requestId()).orElse(null);
            if (replay != null) {
                return replayForCommand(command, replay);
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
                    .map(this::outcomeForReservation)
                    .orElseThrow(() -> new BusinessException(BusinessCode.PARTICIPATION_NOT_FOUND))
                );
        } finally {
            CorrelationContext.clearBusinessContext();
        }
    }
}
