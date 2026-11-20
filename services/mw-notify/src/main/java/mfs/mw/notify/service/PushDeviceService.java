package mfs.mw.notify.service;

import java.time.Clock;
import java.util.Optional;
import mfs.mw.notify.dto.PushTokenEvent;
import mfs.mw.notify.repository.PushDeviceRepository;
import mfs.mw.notify.util.MaskingPolicy;
import mfs.mw.notify.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The push device token registry (ticket 110, {@code notify.push_device}). A wallet has one bound device
 * (mw-auth), so a registration from it replaces the wallet's other devices; a registration older than the
 * stored one is ignored. A device re-bind (mw-auth's {@code DEVICE_BOUND} notice, which names the new
 * device and its binding epoch) revokes the tokens of the wallet's other devices and of that device under any
 * other wallet, so no push goes to an old holder afterwards; the binding is kept ({@code
 * notify.push_device_binding}) so a registration of an older binding that arrives later is refused. Bindings
 * and registrations are ordered by mw-auth's binding epoch only (ticket 110b), never by a clock.
 * Tokens are logged masked to their last 6 characters.
 */
@Service
public class PushDeviceService {

  /** The template of mw-auth's "a new device was bound" notice; its param {@value #DEVICE_PARAM} names the device. */
  public static final String DEVICE_BOUND_TEMPLATE = "DEVICE_BOUND";

  public static final String DEVICE_PARAM = "deviceId";

  /** The notice's param with mw-auth's binding epoch (ticket 110b). */
  public static final String EPOCH_PARAM = "bindingEpoch";

  private static final Logger log = LoggerFactory.getLogger(PushDeviceService.class);

  private final PushDeviceRepository devices;
  private final Clock clock;

  public PushDeviceService(PushDeviceRepository devices, Clock clock) {
    this.devices = devices;
    this.clock = clock;
  }

  /**
   * Stores the token unless its binding is superseded (ticket 110b: a newer binding epoch of the wallet or of
   * the device is known, from a DEVICE_BOUND notice or a newer registration; the registration arrived late, on
   * another topic than the notice) or a newer registration of the same binding is stored. Only mw-auth's
   * binding epoch is compared, never the gateway's {@code registeredAt} with a binding, so clock skew between
   * services cannot let it in.
   */
  @Transactional
  public void register(PushTokenEvent e) {
    long epoch = e.bindingEpoch();
    if (devices.supersedes(e.walletId(), e.deviceId(), epoch)) {
      log.atInfo()
          .addKeyValue("event", "notify.push.token.revoked_late")
          .addKeyValue("correlationId", e.correlationId())
          .addKeyValue("token", MaskingPolicy.mask(SensitiveType.PUSH_TOKEN, e.token()))
          .addKeyValue("bindingEpoch", epoch)
          .log("push token registration of a binding a newer one superseded; ignored");
      return;
    }
    boolean stored =
        devices.upsert(e.walletId(), e.deviceId(), e.token(), e.platform().name(), epoch, e.registeredAt());
    int replaced = stored ? devices.removeOthers(e.walletId(), e.deviceId(), e.token(), epoch) : 0;
    log.atInfo()
        .addKeyValue("event", stored ? "notify.push.token.registered" : "notify.push.token.stale")
        .addKeyValue("correlationId", e.correlationId())
        .addKeyValue("token", MaskingPolicy.mask(SensitiveType.PUSH_TOKEN, e.token()))
        .addKeyValue("platform", e.platform())
        .addKeyValue("replaced", replaced)
        .log(stored ? "push token registered" : "older push token registration ignored");
  }

  /**
   * A device re-bind of mw-auth's binding {@code epoch}: the wallet's other devices' tokens and the device's
   * tokens under any other wallet from older bindings are revoked, and the binding is kept so a registration
   * of an older binding that arrives later is refused (a late notice of an older binding revokes nothing of a
   * newer one). Without an epoch ({@code null}, a notice of an mw-auth before ticket 110b) every such token is
   * revoked and nothing is kept. Rows removed.
   */
  @Transactional
  public int deviceBound(String walletId, String deviceId, Long epoch) {
    int revoked = devices.rebind(walletId, deviceId, epoch, clock.instant());
    log.atInfo()
        .addKeyValue("event", "notify.push.token.revoked")
        .addKeyValue("revoked", revoked)
        .addKeyValue("bindingEpoch", epoch)
        .log("device bound: push tokens of the wallet's other devices revoked");
    return revoked;
  }

  /** The wallet's registered token, if any. */
  public Optional<String> tokenOf(String walletId) {
    return walletId == null ? Optional.empty() : devices.tokenOf(walletId);
  }

  /** The platform of a token that is still registered; empty after a revocation. */
  public Optional<String> platformOf(String token) {
    return devices.platformOf(token);
  }

  /** The push server does not know the token any more: drop it. */
  @Transactional
  public void unregistered(String token) {
    if (devices.deleteToken(token)) {
      log.atInfo()
          .addKeyValue("event", "notify.push.token.unregistered")
          .addKeyValue("token", MaskingPolicy.mask(SensitiveType.PUSH_TOKEN, token))
          .log("push server does not know the token; dropped");
    }
  }
}
