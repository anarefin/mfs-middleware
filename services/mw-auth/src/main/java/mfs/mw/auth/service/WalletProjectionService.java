package mfs.mw.auth.service;

import java.nio.charset.StandardCharsets;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.dto.CryptoEnvelope;
import mfs.mw.auth.dto.WalletStateChanged;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.mapper.WalletEventMapper;
import mfs.mw.auth.messaging.Inbox;
import mfs.mw.auth.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@code auth.wallet} in step with the core (ticket 67, spec 18.7, D5), so login never asks the
 * core synchronously. Each {@code WalletStateChanged} goes through the inbox (consumer {@value
 * #CONSUMER}, deduplicated on eventId) and replaces the row only when its version is higher; an
 * equal or lower one (duplicate, snapshot, out of order) changes nothing. A wallet not yet
 * projected first gets its MSISDN tokenised (keyed hash, mw-crypto TOKENISE_MSISDN) and encrypted
 * (mw-crypto envelope under KEK_COLUMN, AAD the walletId); a known wallet needs no mw-crypto call.
 * mw-crypto unavailable throws {@code MW-SYS-050} before anything is written: the record is retried
 * from the retry topics (fail closed, nothing half-stored).
 */
@Service
public class WalletProjectionService {

  public static final String CONSUMER = "mw-auth.wallet-projection";

  private static final Logger log = LoggerFactory.getLogger(WalletProjectionService.class);

  private final WalletEventMapper mapper;
  private final WalletRepository wallets;
  private final CryptoClient crypto;
  private final Inbox inbox;
  private final JsonMapper json;

  public WalletProjectionService(
      WalletEventMapper mapper, WalletRepository wallets, CryptoClient crypto, Inbox inbox, JsonMapper json) {
    this.mapper = mapper;
    this.wallets = wallets;
    this.crypto = crypto;
    this.inbox = inbox;
    this.json = json;
  }

  /** Handles one {@code core.wallet.event.v1} record body; other core events are skipped. */
  public void handle(String body) {
    mapper.read(body).ifPresent(this::apply);
  }

  void apply(WalletStateChanged event) {
    WalletStateChanged.State s = event.payload();
    AuthWallet row;
    if (wallets.version(s.walletId()).isPresent()) {
      // the MSISDN columns are kept as first written (upsertIfNewer never replaces them)
      row = new AuthWallet(s.walletId(), null, null, null, s.walletType(), s.status(), s.hasPin(), s.birthYear(), s.version());
    } else {
      String msisdn = WalletEventMapper.nationalMsisdn(s.msisdn());
      CryptoClient.MsisdnToken token = crypto.tokeniseMsisdn(msisdn);
      CryptoEnvelope enc =
          crypto.encrypt(
              CryptoClient.KEK_COLUMN,
              msisdn.getBytes(StandardCharsets.UTF_8),
              s.walletId().getBytes(StandardCharsets.UTF_8));
      row =
          new AuthWallet(
              s.walletId(), token.token(), token.kid(), json.writeValueAsString(enc), s.walletType(), s.status(),
              s.hasPin(), s.birthYear(), s.version());
    }
    boolean[] applied = {false};
    inbox.processOnce(CONSUMER, event.eventId(), () -> applied[0] = upsert(row));
    log.atInfo()
        .addKeyValue("event", applied[0] ? "auth.wallet.projected" : "auth.wallet.unchanged")
        .addKeyValue("txnId", event.txnId())
        .addKeyValue("walletStatus", s.status())
        .addKeyValue("version", s.version())
        .log(applied[0] ? "wallet projection updated" : "wallet projection already newer or duplicate");
  }

  private boolean upsert(AuthWallet row) {
    if (row.msisdnToken() == null) {
      return wallets.updateIfNewer(row);
    }
    // a concurrent insert of the same wallet turns into a state update (on conflict)
    return wallets.upsertIfNewer(row);
  }
}
