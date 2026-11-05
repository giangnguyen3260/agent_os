package com.agentos.providerreceipts;

/** Fixed, cause-free internal service-body failure. */
public final class ProviderReceiptFailure extends RuntimeException {
  private final int status;
  private final String code;
  private final int retryAfter;

  public ProviderReceiptFailure(int status, String code) {
    this(status, code, 0);
  }

  public ProviderReceiptFailure(int status, String code, int retryAfter) {
    super(code, null, false, false);
    if (retryAfter < 0
        || retryAfter > 60
        || (retryAfter != 0 && (status != 429 || !"RATE_LIMITED".equals(code))))
      throw new IllegalArgumentException("INVALID_RETRY_AFTER");
    this.retryAfter = retryAfter;
    this.status = status;
    this.code = code;
  }

  public int retryAfter() {
    return retryAfter;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
