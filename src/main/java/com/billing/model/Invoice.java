package com.billing.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Frozen transaction snapshots, gross total, and separate tax and fee rollups. */
public record Invoice(
    String id,
    String currency,
    BigDecimal total,
    OffsetDateTime createdAt,
    List<TransactionRecord> transactions,
    List<TaxLine> taxRollup,
    List<FeeLine> feeRollup) {}
