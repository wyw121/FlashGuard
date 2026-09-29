package com.flashguard.participation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.flashguard.shared.api.ApiResponse;
import com.flashguard.shared.error.BusinessCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1")
public class ParticipationController {

    private final ParticipationService service;

    public ParticipationController(ParticipationService service) {
        this.service = service;
    }

    @PostMapping("/activities/{activityId}/participations")
    public ResponseEntity<ApiResponse<ParticipationResult>> participate(
        @PathVariable long activityId,
        @Valid @RequestBody ParticipationRequest request
    ) {
        ParticipationOutcome outcome = service.participate(new ParticipationCommand(
            activityId,
            request.userId(),
            request.requestId()
        ));
        HttpStatus status = switch (outcome.code()) {
            case REPLAY -> HttpStatus.OK;
            case PENDING -> HttpStatus.ACCEPTED;
            default -> HttpStatus.CREATED;
        };
        return ResponseEntity.status(status).body(ApiResponse.of(
            outcome.code(),
            ParticipationResult.from(outcome)
        ));
    }

    @GetMapping("/participations/{requestId}")
    public ApiResponse<ParticipationResult> findByRequestId(
        @PathVariable @NotBlank @Size(max = 64) String requestId
    ) {
        ParticipationOutcome outcome = service.findByRequestId(requestId);
        return ApiResponse.of(outcome.code(), ParticipationResult.from(outcome));
    }
}
