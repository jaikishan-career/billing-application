package com.billing.api;

/** HTTP-facing failure with an optional existing transaction identity. */
public class ApiException extends RuntimeException {
  private final int status;
  private final String existingId;

  /** Creates an HTTP failure with its status, message, and optional existing resource ID. */
  public ApiException(int status, String message) {
    this(status, message, null);
  }

  /** Creates an HTTP failure with its status, message, and optional existing resource ID. */
  public ApiException(int status, String message, String existingId) {
    super(message);
    this.status = status;
    this.existingId = existingId;
  }

  /** Returns the HTTP status associated with this failure. */
  public int status() {
    return status;
  }

  /** Returns the existing transaction ID for a duplicate, or null for other failures. */
  public String existingId() {
    return existingId;
  }
}
