package mfs.mw.adapter.bank.partner;

/**
 * The raw request and response of one partner call, filled by the adapter and read by the
 * framework wrapper for the masked audit (spec 12.2). Raw bodies carry personal data: they leave
 * this object only through {@link mfs.mw.adapter.bank.partner.template.PartnerAudit}, masked.
 * Written by the call thread and read after the call completes or times out, hence volatile.
 */
public final class ExchangeRecord {

  private volatile String method;
  private volatile String path;
  private volatile String requestBody;
  private volatile Integer responseStatus;
  private volatile String responseBody;

  public void request(String method, String path, String body) {
    this.method = method;
    this.path = path;
    this.requestBody = body;
  }

  public void response(int status, String body) {
    this.responseStatus = status;
    this.responseBody = body;
  }

  public String method() {
    return method;
  }

  public String path() {
    return path;
  }

  public String requestBody() {
    return requestBody;
  }

  public Integer responseStatus() {
    return responseStatus;
  }

  public String responseBody() {
    return responseBody;
  }
}
