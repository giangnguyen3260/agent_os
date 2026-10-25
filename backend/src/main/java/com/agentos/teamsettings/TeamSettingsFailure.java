package com.agentos.teamsettings;

final class TeamSettingsFailure extends RuntimeException {
  private final int status;
  private final String code;

  TeamSettingsFailure(int status, String code) {
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

  static TeamSettingsFailure bad() {
    return new TeamSettingsFailure(400, "BAD_REQUEST");
  }

  static TeamSettingsFailure blocked() {
    return new TeamSettingsFailure(409, "TEAM_SETTINGS_BLOCKED");
  }
}
