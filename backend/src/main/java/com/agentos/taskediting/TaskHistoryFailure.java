package com.agentos.taskediting;

/** Fail closed without exposing persisted private provenance through error dispatch. */
final class TaskHistoryFailure extends IllegalStateException {
  TaskHistoryFailure() {
    super("Invalid saved task edit provenance");
  }

  TaskHistoryFailure(Throwable cause) {
    super("Invalid saved task edit provenance", cause);
  }
}
