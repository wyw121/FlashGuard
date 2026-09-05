package com.flashguard.participation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ParticipationRequest(
    @Positive long userId,
    @NotBlank @Size(max = 64) String requestId
) {
}
