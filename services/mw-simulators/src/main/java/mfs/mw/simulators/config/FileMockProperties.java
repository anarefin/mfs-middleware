package mfs.mw.simulators.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The file mocks of ticket 123 (FRS EXT-05, EXT-06, EXT-17, MCT-05), {@code sim.files}. Only with the mock-control
 * profile; the scheduler jobs run when {@code enabled} (the stack), {@code POST /mock-control/{party}/run} always.
 *
 * @param enabled the polling and statement jobs
 * @param sftp the partner file host (stack: {@code partner-sftp}, login bits; the password from Vault {@code
 *     secret/mw-simulators} {@code sim.files.sftp.password})
 */
@ConfigurationProperties("sim.files")
public record FileMockProperties(boolean enabled, Sftp sftp, Aml aml, TrustBank trustbank, Dw dw) {

  public FileMockProperties {
    sftp = sftp == null ? new Sftp(null, 0, null, null, null, null) : sftp;
    aml = aml == null ? new Aml(null, null, null) : aml;
    trustbank = trustbank == null ? new TrustBank(null, null, null, null, null, null) : trustbank;
    dw = dw == null ? new Dw(null) : dw;
  }

  /**
   * @param hostKey the host key fingerprint to verify ({@code SHA256:...}); empty = any key (the stack's
   *     partner-sftp makes new host keys with every new container; a test double, never production)
   */
  public record Sftp(String host, int port, String username, String password, String hostKey, Duration timeout) {
    public Sftp {
      host = blank(host) ? "partner-sftp" : host;
      port = port <= 0 ? 22 : port;
      username = blank(username) ? "bits" : username;
      password = password == null ? "" : password;
      hostKey = hostKey == null ? "" : hostKey;
      timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    }
  }

  /** The AML vendor: reads {@code inbox}, writes {@code outbox}, rules from {@code seed/<rulesVersion>/aml/rules.json}. */
  public record Aml(String inbox, String outbox, String rulesVersion) {
    public Aml {
      inbox = blank(inbox) ? "aml/in" : inbox;
      outbox = blank(outbox) ? "aml/out" : outbox;
      rulesVersion = blank(rulesVersion) ? "v1" : rulesVersion;
    }
  }

  /**
   * The trust bank.
   *
   * @param walletId the mw-core-mock wallet whose ledger is the trust account (default SETTLEMENT)
   * @param account the account number in {@code :25:}
   */
  public record TrustBank(String outbox, String walletId, String account, String senderBic, String receiverBic, Core core) {
    public TrustBank {
      outbox = blank(outbox) ? "trustbank/out" : outbox;
      walletId = blank(walletId) ? "SETTLEMENT" : walletId;
      account = blank(account) ? "0011223344556" : account;
      senderBic = blank(senderBic) ? "TRSTBDDHXXXX" : senderBic;
      receiverBic = blank(receiverBic) ? "BITSBDDHXXXX" : receiverBic;
      core = core == null ? new Core(null, null, null, null, null) : core;
    }
  }

  /**
   * mw-core-mock's core wallet API ({@code GET /core/v1/statements}, a realm services client-credentials JWT) and
   * test API ({@code GET /core-mock/v1/wallets/{id}}, open).
   *
   * @param tokenUri the token endpoint; empty = no token is sent
   * @param clientSecret from Vault {@code secret/mw-simulators} {@code sim.files.trustbank.core.client-secret}
   */
  public record Core(String baseUrl, String tokenUri, String clientId, String clientSecret, Duration timeout) {
    public Core {
      baseUrl = blank(baseUrl) ? "http://mw-core-mock:8080" : baseUrl;
      tokenUri = tokenUri == null ? "" : tokenUri;
      clientId = blank(clientId) ? "mw-simulators" : clientId;
      clientSecret = clientSecret == null ? "" : clientSecret;
      timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    }
  }

  /** The Data Warehouse drop folder and the schemas of {@code seed/<schemaVersion>/dw/schemas.json}. */
  public record Dw(String inbox) {
    public Dw {
      inbox = blank(inbox) ? "dw/in" : inbox;
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
