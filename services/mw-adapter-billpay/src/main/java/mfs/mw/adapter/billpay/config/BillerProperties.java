package mfs.mw.adapter.billpay.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.billers.<code>}: the biller catalogue (spec 11.2), i.e. which partner instance
 * (aggregator) serves each biller. Static seed until mw-admin-api publishes the catalogue.
 */
@Validated
@ConfigurationProperties("mw")
public record BillerProperties(Map<String, @Valid Biller> billers) {

  public BillerProperties {
    billers = Map.copyOf(billers == null ? Map.of() : billers);
  }

  /**
   * @param name display name
   * @param category ELECTRICITY, WATER, GAS, ...
   * @param partnerId the partner instance (aggregator) that serves the biller
   */
  public record Biller(@NotBlank String name, String category, @NotBlank String partnerId) {}
}
