package com.billing.model;

import java.math.BigDecimal;

/** Named merchant or carrier charge already included in a transaction gross total. */
public record FeeLine(String name, BigDecimal amount) {}
