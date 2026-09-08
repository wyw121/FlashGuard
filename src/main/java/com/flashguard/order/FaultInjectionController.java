package com.flashguard.order;

import com.flashguard.reservation.StockReservationRecord;
import com.flashguard.reservation.StockReservationService;
import com.flashguard.shared.api.ApiResponse;
import com.flashguard.shared.error.BusinessCode;

import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/faults")
public class FaultInjectionController {

    private final ReservationConsumerService reservationConsumerService;
    private final StockReservationService stockReservationService;

    public FaultInjectionController(
        ReservationConsumerService reservationConsumerService,
        StockReservationService stockReservationService
    ) {
        this.reservationConsumerService = reservationConsumerService;
        this.stockReservationService = stockReservationService;
    }

    @PostMapping("/consumer/forced-exception")
    public ResponseEntity<ApiResponse<ForcedExceptionState>> setForcedConsumerException(
        @RequestParam(defaultValue = "true") boolean enabled
    ) {
        reservationConsumerService.setForcedFailureEnabled(enabled);
        return ResponseEntity.ok(ApiResponse.of(
            BusinessCode.SUCCESS,
            new ForcedExceptionState(enabled, stockReservationService.countMqBacklog())
        ));
    }

    @GetMapping("/consumer/forced-exception")
    public ApiResponse<ForcedExceptionState> getForcedConsumerException() {
        return ApiResponse.of(
            BusinessCode.SUCCESS,
            new ForcedExceptionState(
                reservationConsumerService.isForcedFailureEnabled(),
                stockReservationService.countMqBacklog()
            )
        );
    }

    @PostMapping("/consumer/replay")
    public ResponseEntity<ApiResponse<ReplayResult>> replayConsumerByRequestId(
        @RequestParam @NotBlank String requestId,
        @RequestParam(defaultValue = "FORCED_EXCEPTION_TEST") String messageKey
    ) {
        StockReservationRecord reservation = stockReservationService.findByRequestId(requestId)
            .orElse(null);
        if (reservation == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.of(
                BusinessCode.PARTICIPATION_NOT_FOUND,
                null
            ));
        }

        OrderRecord order = reservationConsumerService.consumeReservation(
            reservation.reservationId(),
            messageKey
        );
        return ResponseEntity.ok(ApiResponse.of(
            BusinessCode.SUCCESS,
            new ReplayResult(
                reservation.reservationId(),
                order.orderNo(),
                order.status().name(),
                reservation.status().name()
            )
        ));
    }

    @GetMapping("/reservations/{requestId}")
    public ResponseEntity<ApiResponse<ReservationState>> getReservationByRequestId(
        @PathVariable @NotBlank String requestId
    ) {
        StockReservationRecord reservation = stockReservationService.findByRequestId(requestId)
            .orElse(null);
        if (reservation == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.of(
                BusinessCode.PARTICIPATION_NOT_FOUND,
                null
            ));
        }
        return ResponseEntity.ok(ApiResponse.of(
            BusinessCode.SUCCESS,
            new ReservationState(
                reservation.requestId(),
                reservation.reservationId(),
                reservation.activityId(),
                reservation.userId(),
                reservation.status().name(),
                reservation.messageKey()
            )
        ));
    }

    public record ForcedExceptionState(boolean enabled, long mqBacklog) {}

    public record ReplayResult(
        String reservationId,
        String orderNo,
        String orderStatus,
        String reservationStatus
    ) {}

    public record ReservationState(
        String requestId,
        String reservationId,
        long activityId,
        long userId,
        String status,
        String messageKey
    ) {}
}
