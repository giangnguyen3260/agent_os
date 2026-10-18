package com.agentos.auth;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

public record HumanIdentity(UUID id, String email, String displayName)
    implements Serializable, Principal {
  @com.fasterxml.jackson.annotation.JsonIgnore
  @Override
  public String getName() {
    return id.toString();
  }
}
