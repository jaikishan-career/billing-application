package com.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entry point for the travel transaction and invoice service. */
@SpringBootApplication
public class BillingApplication {
  /** Starts the HTTP service and applies database migrations. */
  public static void main(String[] args) {
    SpringApplication.run(BillingApplication.class, args);
  }
}
