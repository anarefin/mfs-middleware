package mfs.mw.app.gateway.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.app-gateway.linked-accounts.*}: linked bank accounts (ticket 117b, FRS FL-08; {@code
 * service/LinkedAccountService}).
 *
 * @param encryptionKey secret the column key and the lookup-hash key are derived from (at least 32
 *     characters; from Vault, {@code secret/mw-app-gateway}, created once by the dev provisioning;
 *     never in a config file)
 * @param maxPerWallet how many accounts one wallet may link
 */
@Validated
@ConfigurationProperties("mw.app-gateway.linked-accounts")
public record LinkedAccountProperties(
    @NotNull @Size(min = 32) String encryptionKey, @DefaultValue("5") @Min(1) int maxPerWallet) {

  @Override
  public String toString() {
    return "LinkedAccountProperties[encryptionKey=****, maxPerWallet=" + maxPerWallet + "]";
  }
}
