package com.flashguard.participation;

import com.flashguard.order.OrderRecord;
import com.flashguard.shared.error.BusinessCode;

public record ParticipationOutcome(
    BusinessCode code,
    String requestId,
    String orderNo,
    String status
) {

    public static ParticipationOutcome success(OrderRecord order) {
        return from(BusinessCode.SUCCESS, order);
    }

    public static ParticipationOutcome replay(OrderRecord order) {
        return from(BusinessCode.REPLAY, order);
    }

    public static ParticipationOutcome pending(String requestId) {
        return pendingForRequestIdWithStatus(requestId, "PENDING");
    }

    public static ParticipationOutcome pendingForRequestIdWithStatus(String requestId, String status) {
        return new ParticipationOutcome(BusinessCode.PENDING, requestId, null, status);
    }

    public static ParticipationOutcome pendingForRequestId(String requestId) {
        return pending(requestId);
    }

    private static ParticipationOutcome from(BusinessCode code, OrderRecord order) {
        return new ParticipationOutcome(
            code,
            order.requestId(),
            order.orderNo(),
            order.status().name()
        );
    }
}
