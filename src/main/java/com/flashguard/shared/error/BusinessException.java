package com.flashguard.shared.error;

public class BusinessException extends RuntimeException {

    private final BusinessCode code;

    public BusinessException(BusinessCode code) {
        super(code.defaultMessage());
        this.code = code;
    }

    public BusinessCode code() {
        return code;
    }
}
