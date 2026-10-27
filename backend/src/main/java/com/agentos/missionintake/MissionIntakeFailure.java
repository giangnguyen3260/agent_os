package com.agentos.missionintake;

final class MissionIntakeFailure extends RuntimeException {
  private final int status;
  private final String code;

  MissionIntakeFailure(int status, String code) {
    super(code);
    this.status = status;
    this.code = code;
  }

  int status() {
    return status;
  }

  String code() {
    return code;
  }

  static MissionIntakeFailure bad() {
    return new MissionIntakeFailure(400, "BAD_REQUEST");
  }

}
