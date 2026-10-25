package com.agentos.invitation;

final class InvitationFailure extends RuntimeException {
  final int status;
  final String code;

  InvitationFailure(int status, String code) {
    super("Request cannot be completed");
    this.status = status;
    this.code = code;
  }

  static InvitationFailure invalid() {
    return new InvitationFailure(400, "INVALID_INVITATION");
  }

  static InvitationFailure conflict() {
    return new InvitationFailure(409, "CONFLICT");
  }
}
