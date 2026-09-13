package com.flashguard.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.junit.jupiter.api.Test;

class RocketMqProcessSmokeTest {
    @Test
    void startsRealBrokerAndCreatesAnExplicitTopic() throws Exception {
        try (RocketMqProcessSupport broker = new RocketMqProcessSupport()) {
            broker.start();
            DefaultMQAdminExt admin = new DefaultMQAdminExt();
            admin.setNamesrvAddr(broker.nameserverAddress());
            admin.setVipChannelEnabled(false);
            admin.setInstanceName("smoke-" + System.nanoTime());
            admin.start();
            try {
                admin.createAndUpdateTopicConfig(broker.brokerAddress(), new TopicConfig("FlashGuardSmoke", 1, 1, 6));
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(40)).ignoreExceptions()
                    .untilAsserted(() -> assertThat(admin.examineTopicStats("FlashGuardSmoke").getOffsetTable()).isNotEmpty());
                var producer = new org.apache.rocketmq.client.producer.DefaultMQProducer("smoke-producer");
                producer.setNamesrvAddr(broker.nameserverAddress());
                producer.setVipChannelEnabled(false);
                producer.setInstanceName("smoke-producer-" + System.nanoTime());
                producer.start();
                try {
                    assertThat(producer.send(new org.apache.rocketmq.common.message.Message("FlashGuardSmoke",
                        "actual-broker-write".getBytes(java.nio.charset.StandardCharsets.UTF_8))).getSendStatus())
                        .isEqualTo(org.apache.rocketmq.client.producer.SendStatus.SEND_OK);
                } finally { producer.shutdown(); }
            } finally { admin.shutdown(); }
        }
    }
}
