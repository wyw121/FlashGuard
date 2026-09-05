package com.flashguard.participation;

import com.flashguard.order.OrderMapper;
import com.flashguard.order.OrderRecord;
import com.flashguard.shared.error.BusinessCode;
import com.flashguard.shared.error.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ParticipationService {

    private final ParticipationTransaction transaction;
    private final OrderMapper orderMapper;

    public ParticipationService(ParticipationTransaction transaction, OrderMapper orderMapper) {
        this.transaction = transaction;
        this.orderMapper = orderMapper;
    }

    public ParticipationOutcome participate(ParticipationCommand command) {
        try {
            return transaction.execute(command);
        } catch (DuplicateKeyException duplicate) {
            OrderRecord replay = orderMapper.findByRequestId(command.requestId()).orElse(null);
            if (replay != null) {
                return ParticipationOutcome.replay(replay);
            }
            if (orderMapper.findByActivityAndUser(command.activityId(), command.userId()).isPresent()) {
                throw new BusinessException(BusinessCode.DUPLICATE_PURCHASE);
            }
            throw duplicate;
        }
    }

    public ParticipationOutcome findByRequestId(String requestId) {
        OrderRecord order = orderMapper.findByRequestId(requestId)
            .orElseThrow(() -> new BusinessException(BusinessCode.PARTICIPATION_NOT_FOUND));
        return ParticipationOutcome.success(order);
    }
}
