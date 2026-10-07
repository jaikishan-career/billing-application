package com.billing.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Stored transaction or invoice snapshot with separate charges and typed metadata. */
public record TransactionRecord(
    String id,
    String type,
    String externalId,
    String currency,
    OffsetDateTime occurredAt,
    BigDecimal total,
    List<TaxLine> taxLines,
    List<FeeLine> feeLines,
    JsonNode metadata) {}
