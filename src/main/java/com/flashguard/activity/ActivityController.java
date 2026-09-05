package com.flashguard.activity;

import com.flashguard.shared.api.ApiResponse;
import com.flashguard.shared.error.BusinessCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/activities")
public class ActivityController {

    private final ActivityQueryService service;

    public ActivityController(ActivityQueryService service) {
        this.service = service;
    }

    @GetMapping("/{activityId}")
    public ApiResponse<ActivityView> findById(@PathVariable long activityId) {
        return ApiResponse.of(BusinessCode.SUCCESS, service.findById(activityId));
    }
}
