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
 * device) revokes the tokens of the wallet's other devices and of that device under any other wallet, so no
 * push goes to an old holder afterwards; the binding is kept ({@code notify.push_device_binding}) so a
 * registration stamped before it that arrives later is refused.
 * Tokens are logged masked to their last 6 characters.
 */
@Service
public class PushDeviceService {

  /** The template of mw-auth's "a new device was bound" notice; its param {@value #DEVICE_PARAM} names the device. */
  public static final String DEVICE_BOUND_TEMPLATE = "DEVICE_BOUND";

  public static final String DEVICE_PARAM = "deviceId";

  private static final Logger log = LoggerFactory.getLogger(PushDeviceService.class);

  private final PushDeviceRepository devices;
  private final Clock clock;

  public PushDeviceService(PushDeviceRepository devices, Clock clock) {
    this.devices = devices;
    this.clock = clock;
  }

  /**
   * Stores the token unless a re-bind revoked it (a binding at or after {@code registeredAt} of another device
   * to the wallet, or of the device to another wallet: the registration arrived late, on another topic than
   * the notice) or a newer registration of the same device is stored.
   */
  @Transactional
  public void register(PushTokenEvent e) {
    if (devices.revokedBy(e.walletId(), e.deviceId(), e.registeredAt())) {
      log.atInfo()
          .addKeyValue("event", "notify.push.token.revoked_late")
          .addKeyValue("correlationId", e.correlationId())
          .addKeyValue("token", MaskingPolicy.mask(SensitiveType.PUSH_TOKEN, e.token()))
          .log("push token registration older than the re-bind that revoked it; ignored");
      return;
    }
    boolean stored = devices.upsert(e.walletId(), e.deviceId(), e.token(), e.platform().name(), e.registeredAt());
    int replaced = stored ? devices.removeOthers(e.walletId(), e.deviceId(), e.token()) : 0;
    log.atInfo()
        .addKeyValue("event", stored ? "notify.push.token.registered" : "notify.push.token.stale")
        .addKeyValue("correlationId", e.correlationId())
        .addKeyValue("token", MaskingPolicy.mask(SensitiveType.PUSH_TOKEN, e.token()))
        .addKeyValue("platform", e.platform())
        .addKeyValue("replaced", replaced)
        .log(stored ? "push token registered" : "older push token registration ignored");
  }

  /**
   * A device re-bind, as of now (the notice carries no time; the moment it is accepted is at or after the
   * binding): the wallet's other devices' tokens and the device's tokens under any other wallet are revoked,
   * and the binding is kept so a registration stamped before it that arrives later is refused. Rows removed.
   */
  @Transactional
  public int deviceBound(String walletId, String deviceId) {
    int revoked = devices.rebind(walletId, deviceId, clock.instant());
    log.atInfo()
        .addKeyValue("event", "notify.push.token.revoked")
        .addKeyValue("revoked", revoked)
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
