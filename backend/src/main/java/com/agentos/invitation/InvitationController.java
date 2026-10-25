package com.agentos.invitation;

import com.agentos.auth.HumanIdentity;
import com.agentos.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvitationController {
  private static final String BASE = "/api/v1/organizations/{org}/invitations";
  private final InvitationManagement management;
  private final InvitationRedemption redemption;
  private final ObjectMapper json;

  InvitationController(
      InvitationManagement management, InvitationRedemption redemption, ObjectMapper json) {
    this.management = management;
    this.redemption = redemption;
    this.json = json;
  }

  private static void query(HttpServletRequest request, String... allowed) {
    Set<String> keys = Set.of(allowed);
    request
        .getParameterMap()
        .forEach(
            (k, v) -> {
              if (!keys.contains(k) || v.length != 1) InvitationWire.bad();
            });
  }

  private static UUID key(HttpServletRequest request, String key) {
    if (java.util.Collections.list(request.getHeaders("Idempotency-Key")).size() != 1)
      InvitationWire.bad();
    return InvitationWire.uuid(key);
  }

  @GetMapping(BASE)
  public ApiResponse<InvitationDtos.Page> list(
      @PathVariable String org,
      @AuthenticationPrincipal HumanIdentity h,
      HttpServletRequest request) {
    query(request, "status", "page");
    String status = request.getParameter("status");
    if (status == null) status = "ALL";
    if (!Set.of("ALL", "PENDING", "ACCEPTED", "REVOKED", "EXPIRED").contains(status))
      InvitationWire.bad();
    String raw = request.getParameter("page");
    if (raw != null && !raw.matches("0|[1-9][0-9]{0,5}")) InvitationWire.bad();
    int page = raw == null ? 0 : Integer.parseInt(raw);
    if (page > 100000) InvitationWire.bad();
    return ApiResponse.ok(management.list(InvitationWire.uuid(org), h, status, page));
  }

  @GetMapping(BASE + "/{id}/context")
  public ApiResponse<InvitationDtos.Context> context(
      @PathVariable String org,
      @PathVariable String id,
      @AuthenticationPrincipal HumanIdentity h,
      HttpServletRequest request) {
    query(request);
    return ApiResponse.ok(management.context(InvitationWire.uuid(org), InvitationWire.uuid(id), h));
  }

  @GetMapping(BASE + "/receipts/{key}")
  public ApiResponse<InvitationDtos.Receipt> receipt(
      @PathVariable String org,
      @PathVariable String key,
      @AuthenticationPrincipal HumanIdentity h,
      HttpServletRequest request) {
    query(request);
    return ApiResponse.ok(
        management.receipt(InvitationWire.uuid(org), h, InvitationWire.uuid(key)));
  }

  @PostMapping(BASE)
  public ResponseEntity<ApiResponse<InvitationDtos.Receipt>> create(
      @PathVariable String org,
      @AuthenticationPrincipal HumanIdentity h,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody String raw,
      HttpServletRequest request) {
    query(request);
    var n = InvitationWire.body(json, raw, "email", "acknowledgement");
    InvitationWire.ack(n);
    return result(
        management.mutate(
            InvitationWire.uuid(org),
            null,
            h,
            key(request, key),
            "CREATE_INVITATION",
            InvitationWire.email(InvitationWire.text(n, "email")),
            null));
  }

  @PostMapping({BASE + "/{id}/resend", BASE + "/{id}/revoke"})
  public ResponseEntity<ApiResponse<InvitationDtos.Receipt>> mutate(
      @PathVariable String org,
      @PathVariable String id,
      @AuthenticationPrincipal HumanIdentity h,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody String raw,
      HttpServletRequest request) {
    query(request);
    var n = InvitationWire.body(json, raw, "expectedRevision", "acknowledgement");
    InvitationWire.ack(n);
    String op =
        request.getRequestURI().endsWith("/resend") ? "RESEND_INVITATION" : "REVOKE_INVITATION";
    return result(
        management.mutate(
            InvitationWire.uuid(org),
            InvitationWire.uuid(id),
            h,
            key(request, key),
            op,
            null,
            InvitationWire.revision(n)));
  }

  private ResponseEntity<ApiResponse<InvitationDtos.Receipt>> result(InvitationDtos.Mutation m) {
    return ResponseEntity.status(m.replay() ? 200 : 201).body(ApiResponse.ok(m.receipt()));
  }

  @PostMapping("/api/v1/auth/invitations/inspect")
  public ApiResponse<InvitationDtos.Inspection> inspect(
      @RequestBody String raw,
      @AuthenticationPrincipal HumanIdentity h,
      HttpServletRequest request) {
    query(request);
    var n = InvitationWire.body(json, raw, "token");
    return ApiResponse.ok(redemption.inspect(InvitationWire.text(n, "token"), h));
  }

  @PostMapping("/api/v1/auth/invitations/accept")
  public ApiResponse<InvitationDtos.Acceptance> accept(
      @RequestBody String raw,
      @AuthenticationPrincipal HumanIdentity h,
      HttpServletRequest request) {
    query(request);
    var n = InvitationWire.body(json, raw, "token", "displayName", "password");
    return ApiResponse.ok(
        redemption.accept(
            InvitationWire.text(n, "token"),
            n.get("displayName").isNull() ? null : InvitationWire.text(n, "displayName"),
            n.get("password").isNull() ? null : InvitationWire.text(n, "password"),
            h));
  }
}
