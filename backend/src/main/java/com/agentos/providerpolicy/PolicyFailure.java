package com.agentos.providerpolicy;

public final class PolicyFailure extends RuntimeException {
  private final String code;
  private final int status;
  private final int retryAfter;

  public PolicyFailure(int status, String code) {
    this(status, code, 0);
  }

  public PolicyFailure(int status, String code, int retryAfter) {
    super(code, null, false, false);
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }

  public String code() {
    return code;
  }

  public int status() {
    return status;
  }

  public int retryAfter() {
    return retryAfter;
  }

  static PolicyFailure unavailable() {
    return new PolicyFailure(503, "PROVIDER_CONNECTION_UNAVAILABLE");
  }

  static PolicyFailure forbidden() {
    return new PolicyFailure(403, "FORBIDDEN");
  }
}
