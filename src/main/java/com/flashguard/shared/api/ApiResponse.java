package com.flashguard.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flashguard.shared.error.BusinessCode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(String code, String message, T data) {

    public static <T> ApiResponse<T> of(BusinessCode code, T data) {
        return new ApiResponse<>(code.name(), code.defaultMessage(), data);
    }

    public static ApiResponse<Void> error(BusinessCode code) {
        return of(code, null);
    }
}
