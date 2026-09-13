package com.devpilot.exception;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.dao.DataIntegrityViolationException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private ResponseEntity<ApiError> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiError.of(status.value(), message));
    }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException ex) { return error(ex.getStatus(), ex.getMessage()); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> validation(Exception ex) { return error(HttpStatus.BAD_REQUEST, "Invalid request"); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(Exception ex) { return error(HttpStatus.CONFLICT, "Resource already exists"); }
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> unauthorized(Exception ex) { return error(HttpStatus.UNAUTHORIZED, "Authentication required"); }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(Exception ex) { return error(HttpStatus.FORBIDDEN, "Access denied"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) { return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"); }
}
