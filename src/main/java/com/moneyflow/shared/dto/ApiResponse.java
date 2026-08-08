package com.moneyflow.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private final boolean success;
    private final String message;
    private final T data;
    private final ApiError error;
    private final String warning;
    private final LocalDateTime timestamp;

    private ApiResponse(boolean success, String message, T data, ApiError error, String warning) {
        this.success = success;
        this.message = message;
        this.data = data;
        this.error = error;
        this.warning = warning;
        this.timestamp = LocalDateTime.now();
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, message, data, null, null);
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "Operation Successful", data, null, null);
    }

    public static <T> ApiResponse<T> successWithWarning(
            T data, String message, String warning) {
        return new ApiResponse<>(true, message, data, null, warning);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, null, null, new ApiError(code, message), null);
    }

    public record ApiError(String code, String message) {
    }
}
