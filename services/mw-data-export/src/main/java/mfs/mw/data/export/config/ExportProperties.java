package mfs.mw.data.export.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The warehouse export (ticket 128), under {@code mw.export}: the daily job, the SFTP drop, the
 * core snapshot and the mw-crypto client.
 */
@Validated
@ConfigurationProperties("mw.export")
public record ExportProperties(
    @NotBlank String cron,
    @NotBlank String zone,
    boolean consumersEnabled,
    @NotNull Sftp sftp,
    @NotNull Core core,
    @NotNull Crypto crypto) {

  /** The Data Warehouse drop folder on the shared SFTP host. */
  public record Sftp(
      @NotBlank String host,
      int port,
      @NotBlank String username,
      String password,
      @NotBlank String directory,
      boolean insecureHostKey,
      String fingerprint) {}

  /** Core wallet reads for the snapshot file. An empty id list writes a header-only file. */
  public record Core(@NotBlank String baseUrl, List<String> walletIds) {
    public Core {
      walletIds = walletIds == null ? List.of() : List.copyOf(walletIds);
    }
  }

  /** mw-crypto tokenise calls. Off in tests, which supply their own {@code FieldTokeniser}. */
  public record Crypto(
      boolean enabled,
      @NotBlank String baseUrl,
      @NotBlank String tokenUri,
      @NotBlank String clientId,
      String clientSecret,
      @NotNull Duration connectTimeout,
      @NotNull Duration readTimeout) {}
}
