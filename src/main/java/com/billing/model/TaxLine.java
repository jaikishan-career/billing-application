package com.billing.model;

import java.math.BigDecimal;

/** Named tax included in the gross total, with an optional fractional tax rate. */
public record TaxLine(String name, BigDecimal rate, BigDecimal amount) {}
