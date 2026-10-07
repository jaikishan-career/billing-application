package com.billing.api;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Database connectivity endpoint. */
@RestController
public class HealthController {
  private final JdbcTemplate jdbc;

  /** Creates the component with its required collaborators. */
  public HealthController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Returns health status after successfully querying PostgreSQL. */
  @GetMapping("/health")
  public Map<String, String> health() {
    jdbc.queryForObject("SELECT 1", Integer.class);
    return Map.of("status", "ok");
  }
}
