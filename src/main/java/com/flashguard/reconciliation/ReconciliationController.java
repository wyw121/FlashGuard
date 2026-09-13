package com.flashguard.reconciliation;

import com.flashguard.shared.api.ApiResponse;
import com.flashguard.shared.error.BusinessCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/activities/{activityId}")
    public ResponseEntity<ApiResponse<ReconciliationResult>> reconcile(@PathVariable long activityId) {
        if (activityId <= 0) {
            throw new com.flashguard.shared.error.BusinessException(BusinessCode.VALIDATION_ERROR);
        }
        return ResponseEntity.ok(ApiResponse.of(
            BusinessCode.SUCCESS,
            reconciliationService.reconcileActivity(activityId)
        ));
    }
}
