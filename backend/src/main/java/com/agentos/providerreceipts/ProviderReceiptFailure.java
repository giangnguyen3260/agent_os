package com.agentos.providerreceipts;

/** Fixed, cause-free internal service-body failure. */
public final class ProviderReceiptFailure extends RuntimeException {
  private final int status;
  private final String code;

  public ProviderReceiptFailure(int status, String code) {
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
