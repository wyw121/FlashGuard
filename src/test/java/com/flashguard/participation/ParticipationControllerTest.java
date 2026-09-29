package com.flashguard.participation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashguard.shared.error.BusinessCode;
import com.flashguard.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ParticipationController.class)
class ParticipationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ParticipationService service;

    @Test
    void successfulParticipationReturnsCreated() throws Exception {
        when(service.participate(any())).thenReturn(outcome(BusinessCode.SUCCESS));

        mockMvc.perform(post("/api/v1/activities/1001/participations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.requestId").value("request-1"))
            .andExpect(jsonPath("$.data.orderNo").value("01K4C1YJ000000000000000001"))
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.code").doesNotExist());
    }

    @Test
    void replayReturnsOkAndExistingOrder() throws Exception {
        when(service.participate(any())).thenReturn(outcome(BusinessCode.REPLAY));

        mockMvc.perform(post("/api/v1/activities/1001/participations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("REPLAY"))
            .andExpect(jsonPath("$.data.orderNo").value("01K4C1YJ000000000000000001"));
    }

    @Test
    void pendingReturnsAcceptedAndMarksPendingStatus() throws Exception {
        when(service.participate(any())).thenReturn(outcome(BusinessCode.PENDING, "PENDING"));

        mockMvc.perform(post("/api/v1/activities/1001/participations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.code").value("PENDING"))
            .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void businessRejectionsUseTheirHttpContract() throws Exception {
        when(service.participate(any()))
            .thenThrow(new BusinessException(BusinessCode.SOLD_OUT));

        mockMvc.perform(post("/api/v1/activities/1001/participations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SOLD_OUT"));
    }

    @Test
    void missingActivityReturnsNotFound() throws Exception {
        when(service.participate(any()))
            .thenThrow(new BusinessException(BusinessCode.ACTIVITY_NOT_FOUND));

        mockMvc.perform(post("/api/v1/activities/9999/participations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
    }

    @Test
    void duplicatePurchaseReturnsConflict() throws Exception {
        when(service.participate(any()))
            .thenThrow(new BusinessException(BusinessCode.DUPLICATE_PURCHASE));

        mockMvc.perform(post("/api/v1/activities/1001/participations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_PURCHASE"));
    }

    @Test
    void invalidUserAndRequestIdentifiersReturnValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/activities/1001/participations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":0,\"requestId\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        String tooLong = "x".repeat(65);
        mockMvc.perform(post("/api/v1/activities/1001/participations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":2001,\"requestId\":\"" + tooLong + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void participationWithGetReturnsMethodNotAllowedAndAllowedMethod() throws Exception {
        mockMvc.perform(get("/api/v1/activities/1001/participations"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().string("Allow", "POST"))
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));

        verifyNoInteractions(service);
    }

    @Test
    void plainTextBodyReturnsUnsupportedMediaTypeWithAcceptedFormats() throws Exception {
        mockMvc.perform(post("/api/v1/activities/1001/participations")
                .contentType(MediaType.TEXT_PLAIN)
                .content(validBody()))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(header().string("Accept", org.hamcrest.Matchers.containsString("application/json")))
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        verifyNoInteractions(service);
    }

    @Test
    void formBodyReturnsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/v1/activities/1001/participations")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("userId=10001&requestId=postman-001"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        verifyNoInteractions(service);
    }

    @Test
    void participationCanBeQueriedByRequestId() throws Exception {
        when(service.findByRequestId("request-1")).thenReturn(outcome(BusinessCode.SUCCESS));

        mockMvc.perform(get("/api/v1/participations/request-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    void missingParticipationReturnsNotFound() throws Exception {
        when(service.findByRequestId("missing"))
            .thenThrow(new BusinessException(BusinessCode.PARTICIPATION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/participations/missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PARTICIPATION_NOT_FOUND"));
    }

    private ParticipationOutcome outcome(BusinessCode code) {
        return outcome(code, "CONFIRMED");
    }

    private ParticipationOutcome outcome(BusinessCode code, String status) {
        return new ParticipationOutcome(
            code,
            "request-1",
            "01K4C1YJ000000000000000001",
            status
        );
    }

    private String validBody() {
        return "{\"userId\":2001,\"requestId\":\"request-1\"}";
    }
}
