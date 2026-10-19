package com.agentos.auth;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

public record HumanIdentity(
    UUID id,
    String email,
    String displayName,
    @com.fasterxml.jackson.annotation.JsonIgnore long credentialVersion)
    implements Serializable, Principal {
  public HumanIdentity(UUID id, String email, String displayName) {
    this(id, email, displayName, 0L);
  }

  @com.fasterxml.jackson.annotation.JsonIgnore
  @Override
  public String getName() {
    return id.toString();
  }
}
