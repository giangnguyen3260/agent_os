package com.agentos.taskowner;

final class TaskOwnerFailure extends RuntimeException {
  private final int status;
  private final String code;

  TaskOwnerFailure(int status, String code) {
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

  static TaskOwnerFailure bad() {
    return new TaskOwnerFailure(400, "BAD_REQUEST");
  }

  static TaskOwnerFailure blocked() {
    return new TaskOwnerFailure(409, "TASK_OWNER_BLOCKED");
  }
}
