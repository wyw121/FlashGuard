package com.flashguard.activity;

import com.flashguard.shared.error.BusinessCode;
import com.flashguard.shared.error.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class ActivityQueryService {

    private final ActivityMapper activityMapper;

    public ActivityQueryService(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    public ActivityView findById(long activityId) {
        return activityMapper.findById(activityId)
            .orElseThrow(() -> new BusinessException(BusinessCode.ACTIVITY_NOT_FOUND));
    }
}
