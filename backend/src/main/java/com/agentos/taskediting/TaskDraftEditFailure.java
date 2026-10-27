package com.agentos.taskediting;

final class TaskDraftEditFailure extends RuntimeException {
  private final int status;
  private final String code;

  TaskDraftEditFailure(int status, String code) {
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

  static TaskDraftEditFailure bad() {
    return new TaskDraftEditFailure(400, "BAD_REQUEST");
  }

  static TaskDraftEditFailure blocked() {
    return new TaskDraftEditFailure(409, "TASK_EDIT_BLOCKED");
  }
}
