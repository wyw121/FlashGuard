package com.flashguard.activity;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import com.flashguard.shared.error.BusinessCode;
import com.flashguard.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ActivityController.class)
class ActivityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActivityQueryService service;

    @Test
    void returnsActivityDetails() throws Exception {
        when(service.findById(1001L)).thenReturn(new ActivityView(
            1001L,
            "Flash sale",
            LocalDateTime.of(2026, 9, 5, 3, 0),
            LocalDateTime.of(2026, 9, 5, 5, 0),
            ActivityStatus.ACTIVE,
            100,
            42
        ));

        mockMvc.perform(get("/api/v1/activities/1001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.id").value(1001))
            .andExpect(jsonPath("$.data.availableStock").value(42));
    }

    @Test
    void unknownActivityReturnsNotFoundWithoutExceptionDetails() throws Exception {
        when(service.findById(9999L))
            .thenThrow(new BusinessException(BusinessCode.ACTIVITY_NOT_FOUND));

        mockMvc.perform(get("/api/v1/activities/9999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Activity not found"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unexpectedFailureReturnsSanitizedInternalError() throws Exception {
        when(service.findById(1001L)).thenThrow(new IllegalStateException("sensitive SQL"));

        mockMvc.perform(get("/api/v1/activities/1001"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("Internal server error"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("SQL")
            )));
    }
}
