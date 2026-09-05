package com.flashguard.participation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.flashguard.activity.ActivityMapper;
import com.flashguard.activity.ActivityStatus;
import com.flashguard.activity.ActivityView;
import com.flashguard.inventory.ActivityStockMapper;
import com.flashguard.order.OrderMapper;
import com.flashguard.order.OrderRecord;
import com.flashguard.order.OrderStatus;
import com.flashguard.shared.error.BusinessCode;
import com.flashguard.shared.error.BusinessException;
import com.flashguard.shared.id.OrderNumberGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParticipationTransaction {

    private final ActivityMapper activityMapper;
    private final ActivityStockMapper stockMapper;
    private final OrderMapper orderMapper;
    private final OrderNumberGenerator orderNumberGenerator;
    private final Clock clock;

    @Autowired
    public ParticipationTransaction(
        ActivityMapper activityMapper,
        ActivityStockMapper stockMapper,
        OrderMapper orderMapper,
        OrderNumberGenerator orderNumberGenerator
    ) {
        this(activityMapper, stockMapper, orderMapper, orderNumberGenerator, Clock.systemUTC());
    }

    ParticipationTransaction(
        ActivityMapper activityMapper,
        ActivityStockMapper stockMapper,
        OrderMapper orderMapper,
        OrderNumberGenerator orderNumberGenerator,
        Clock clock
    ) {
        this.activityMapper = activityMapper;
        this.stockMapper = stockMapper;
        this.orderMapper = orderMapper;
        this.orderNumberGenerator = orderNumberGenerator;
        this.clock = clock;
    }

    @Transactional
    public ParticipationOutcome execute(ParticipationCommand command) {
        OrderRecord replay = orderMapper.findByRequestId(command.requestId()).orElse(null);
        if (replay != null) {
            return ParticipationOutcome.replay(replay);
        }

        ActivityView activity = activityMapper.findById(command.activityId())
            .orElseThrow(() -> new BusinessException(BusinessCode.ACTIVITY_NOT_FOUND));
        validateActivity(activity);

        if (stockMapper.decrementIfAvailable(command.activityId()) != 1) {
            throw new BusinessException(BusinessCode.SOLD_OUT);
        }

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        OrderRecord order = new OrderRecord(
            0L,
            orderNumberGenerator.next(),
            command.requestId(),
            command.activityId(),
            command.userId(),
            OrderStatus.CONFIRMED,
            now,
            now
        );
        orderMapper.insert(order);
        return ParticipationOutcome.success(order);
    }

    private void validateActivity(ActivityView activity) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (activity.status() == ActivityStatus.ENDED) {
            throw new BusinessException(BusinessCode.ACTIVITY_ENDED);
        }
        if (now.isBefore(activity.startTime())) {
            throw new BusinessException(BusinessCode.ACTIVITY_NOT_STARTED);
        }
        if (activity.status() != ActivityStatus.ACTIVE || !now.isBefore(activity.endTime())) {
            throw new BusinessException(BusinessCode.ACTIVITY_ENDED);
        }
    }
}
