package com.billing.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Decodes JSONB metadata without discarding optional type-specific fields. */
@Component
public class JsonStorage {
  private final ObjectMapper mapper;

  /** Creates the component with its required collaborators. */
  public JsonStorage(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** Parses persisted JSON metadata, rejecting invalid stored JSON as an internal failure. */
  public JsonNode read(String value) {
    try {
      return mapper.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException("Stored metadata is invalid JSON", e);
    }
  }
}
