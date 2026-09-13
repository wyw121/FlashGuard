package com.flashguard.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.remoting.protocol.admin.ConsumeStats;
import org.apache.rocketmq.remoting.protocol.admin.OffsetWrapper;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.junit.jupiter.api.Test;

class RocketMqBacklogMetricsTest {
    @Test
    void exposesBrokerOffsetsAndMarksUnavailableRatherThanPretendingZeroBacklog() throws Exception {
        DefaultMQAdminExt admin = mock(DefaultMQAdminExt.class);
        ConsumeStats stats = new ConsumeStats();
        OffsetWrapper offset = new OffsetWrapper();
        offset.setBrokerOffset(12); offset.setConsumerOffset(5);
        stats.getOffsetTable().put(new MessageQueue("topic", "broker", 0), offset);
        when(admin.examineConsumeStats("group")).thenReturn(stats).thenThrow(new RuntimeException("offline"));
        when(admin.examineTopicStats("%DLQ%group")).thenThrow(new org.apache.rocketmq.client.exception.MQClientException(17, "not created"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RocketMqBacklogMetrics metrics = new RocketMqBacklogMetrics(admin, "group", registry);
        metrics.refresh();
        assertThat(registry.get("flashguard_mq_backlog").gauge().value()).isEqualTo(7);
        assertThat(registry.get("flashguard_mq_dlq_messages").gauge().value()).isZero();
        metrics.refresh();
        assertThat(registry.get("flashguard_mq_backlog").gauge().value()).isEqualTo(-1);
    }
}
