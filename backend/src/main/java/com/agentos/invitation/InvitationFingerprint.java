package com.agentos.invitation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.TreeMap;
import java.util.UUID;

final class InvitationFingerprint {
  private InvitationFingerprint() {}

  static String hash(
      ObjectMapper json,
      UUID org,
      UUID actor,
      UUID target,
      String operation,
      String email,
      Integer expected) {
    var canonical = new TreeMap<String, Object>();
    canonical.put("acknowledgement", true);
    canonical.put("actorId", actor);
    canonical.put("domain", "MEMBER_INVITATIONS");
    canonical.put("email", email);
    canonical.put("expectedRevision", expected);
    canonical.put("invitationId", target);
    canonical.put("operation", operation);
    canonical.put("organizationId", org);
    canonical.put("version", 1);
    try {
      return InvitationWire.digest(
          json.copy()
              .setSerializationInclusion(
                  com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
              .writer()
              .without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
              .writeValueAsString(canonical));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Cannot hash invitation command", e);
    }
  }
}
