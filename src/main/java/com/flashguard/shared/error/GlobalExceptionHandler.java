package com.flashguard.shared.error;

import jakarta.validation.ConstraintViolationException;

import com.flashguard.shared.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        return ResponseEntity
            .status(statusFor(exception.code()))
            .body(ApiResponse.error(exception.code()));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HandlerMethodValidationException.class,
        ConstraintViolationException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception exception) {
        return ResponseEntity
            .badRequest()
            .body(ApiResponse.error(BusinessCode.VALIDATION_ERROR));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        LOGGER.error("Unhandled request failure", exception);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(BusinessCode.INTERNAL_ERROR));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(
        HttpRequestMethodNotSupportedException exception
    ) {
        return ResponseEntity
            .status(HttpStatus.METHOD_NOT_ALLOWED)
            .headers(exception.getHeaders())
            .body(ApiResponse.error(BusinessCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(
        HttpMediaTypeNotSupportedException exception
    ) {
        return ResponseEntity
            .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .headers(exception.getHeaders())
            .body(ApiResponse.error(BusinessCode.UNSUPPORTED_MEDIA_TYPE));
    }

    private HttpStatus statusFor(BusinessCode code) {
        return switch (code) {
            case ACTIVITY_NOT_FOUND, PARTICIPATION_NOT_FOUND, ORDER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ACTIVITY_NOT_STARTED, ACTIVITY_ENDED, SOLD_OUT, DUPLICATE_PURCHASE ->
                HttpStatus.CONFLICT;
            case THROTTLED -> HttpStatus.TOO_MANY_REQUESTS;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case PENDING -> HttpStatus.ACCEPTED;
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case SUCCESS, REPLAY -> HttpStatus.OK;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
