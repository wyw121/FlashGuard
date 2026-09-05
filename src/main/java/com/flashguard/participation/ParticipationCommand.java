package com.flashguard.participation;

public record ParticipationCommand(long activityId, long userId, String requestId) {
}
