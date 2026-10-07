package com.billing.api;

/** Validated SQL page bounds with a maximum limit of 100. */
public record Pagination(int limit, int offset) {
  /** Rejects limits outside 1..100 and negative offsets. */
  public Pagination {
    if (limit < 1 || limit > 100 || offset < 0) {
      throw new ApiException(400, "limit must be 1..100 and offset must be nonnegative");
    }
  }
}
