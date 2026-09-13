package com.flashguard.mq;

import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.TransactionMQProducer;

/** Registers the recovery group with its broker even when a restarted instance receives no new traffic. */
public class RecoverableTransactionProducer extends TransactionMQProducer {
    private final String topic;

    public RecoverableTransactionProducer(String group, String topic) {
        super(group);
        this.topic = topic;
    }

    @Override public void start() throws MQClientException {
        super.start();
        try {
            var client = getDefaultMQProducerImpl().getmQClientFactory();
            // The admin-style fetchPublishMessageQueues call alone does not populate brokerAddrTable.
            client.updateTopicRouteInfoFromNameServer(topic);
            if (fetchPublishMessageQueues(topic).isEmpty())
                throw new MQClientException("Reservation topic has no writable queues: " + topic, null);
            // A periodic heartbeat can hold the same lock during startup. The normal client
            // scheduler continues registration using the route just loaded even if this attempt loses it.
            client.sendHeartbeatToAllBrokerWithLock();
        } catch (MQClientException | RuntimeException failure) {
            shutdown();
            throw failure;
        }
    }
}
