package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One biller of the catalogue. {@code partnerId} (the aggregator serving it) comes from
 * mw-adapter-billpay and is never shown to the app (null in the app view).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Biller(String code, String name, String category, String partnerId) {

  public Biller withoutPartner() {
    return new Biller(code, name, category, null);
  }
}
