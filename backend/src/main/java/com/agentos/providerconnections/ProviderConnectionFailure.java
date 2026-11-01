package com.agentos.providerconnections;

/** Safe internal failure, deliberately without database or credential details. */
public final class ProviderConnectionFailure extends RuntimeException {
  private final int status;
  private final String code;

  public ProviderConnectionFailure(int status, String code) {
    super(code, null, false, false);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
