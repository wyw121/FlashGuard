package com.flashguard.participation;

public record ParticipationResult(String requestId, String orderNo, String status) {

    public static ParticipationResult from(ParticipationOutcome outcome) {
        return new ParticipationResult(outcome.requestId(), outcome.orderNo(), outcome.status());
    }
}
