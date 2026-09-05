package com.flashguard;

import com.flashguard.activity.ActivityMapper;
import com.flashguard.inventory.ActivityStockMapper;
import com.flashguard.order.OrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
class FlashGuardApplicationTest {

    @MockitoBean
    private ActivityMapper activityMapper;

    @MockitoBean
    private ActivityStockMapper stockMapper;

    @MockitoBean
    private OrderMapper orderMapper;

    @Test
    void applicationContextLoads() {
    }
}
