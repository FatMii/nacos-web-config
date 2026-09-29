package io.github.fatmii.nacoswebconfig.mvc;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class WebConfigErrorHandler {
    @ExceptionHandler(WebConfigRequestException.class)
    ResponseEntity<Map<String, String>> invalid(WebConfigRequestException exception) {
        if ("CONNECTION_LIMIT".equals(exception.code())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("code", exception.code(), "message", exception.getMessage()));
        }
        if ("MODULE_STOPPED".equals(exception.code())) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, exception.code(), exception.getMessage());
        }
        if ("UNAUTHENTICATED".equals(exception.code())) {
            return error(HttpStatus.UNAUTHORIZED, exception.code(), exception.getMessage());
        }
        return error(HttpStatus.BAD_REQUEST, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Map<String, String>> sseRequired() {
        return error(HttpStatus.NOT_ACCEPTABLE, "SSE_REQUIRED", "Accept must allow text/event-stream");
    }

    private ResponseEntity<Map<String, String>> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", code, "message", message));
    }
}
