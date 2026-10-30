package com.agentos.taskdependencies;

final class TaskDependenciesFailure extends RuntimeException {
  private final int status;
  private final String code;

  TaskDependenciesFailure(int status, String code) {
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

  static TaskDependenciesFailure bad() {
    return new TaskDependenciesFailure(400, "BAD_REQUEST");
  }

  static TaskDependenciesFailure blocked() {
    return new TaskDependenciesFailure(409, "TASK_DEPENDENCIES_BLOCKED");
  }
}
