package com.billing.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps validation, identity, and concurrency failures to JSON HTTP responses. */
@RestControllerAdvice
public class ErrorHandler {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, Object>> domain(ApiException error) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("message", error.getMessage());
    if (error.existingId() != null) {
      body.put("existing_id", error.existingId());
    }
    return ResponseEntity.status(error.status()).body(body);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<Map<String, String>> invalid(Exception error) {
    return ResponseEntity.badRequest().body(Map.of("message", "Invalid JSON or query parameter"));
  }

  @ExceptionHandler(ConcurrencyFailureException.class)
  ResponseEntity<Map<String, String>> concurrent(ConcurrencyFailureException error) {
    return ResponseEntity.status(409)
        .body(Map.of("message", "Concurrent write could not acquire a lock; retry"));
  }
}
