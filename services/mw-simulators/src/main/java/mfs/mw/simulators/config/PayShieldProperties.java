package mfs.mw.simulators.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sim.payshield}: the Thales payShield 10K simulator (ticket 64, spec 18.8, D23).
 *
 * @param enabled start the TCP listener
 * @param port host command port (1500, internal network only; 0 = any free port, tests)
 * @param headerLength characters of the message header the host sends and gets back (4)
 * @param lmkStore {@code pkcs11}: the LMK is a token object of the SoftHSM {@code paysim} token (the
 *     stack); {@code memory}: a fresh LMK in memory (host tests)
 * @param allowNonPanTranslation the payShield security setting "allow translation to non-PAN
 *     formats" (default off: {@code CA} / {@code CC} from ISO 0, 3 or 4 into ISO 1 answer {@code 69};
 *     read by {@code service/PayShieldService})
 * @param pkcs11 the {@code paysim} token
 */
@ConfigurationProperties("sim.payshield")
public record PayShieldProperties(
    boolean enabled, int port, int headerLength, String lmkStore, boolean allowNonPanTranslation, Pkcs11 pkcs11) {

  public PayShieldProperties {
    headerLength = headerLength == 0 ? 4 : headerLength;
    lmkStore = lmkStore == null || lmkStore.isBlank() ? "pkcs11" : lmkStore;
    pkcs11 = pkcs11 == null ? new Pkcs11(null, 0, null, null) : pkcs11;
  }

  /**
   * The SoftHSM {@code paysim} token through the image's p11-kit proxy (its one module dials
   * softhsm:5658; deploy/softhsm/README.md).
   *
   * @param library the PKCS#11 library ({@code /usr/lib/libp11-kit.so.0})
   * @param slotListIndex the token's slot (0: the proxy shows only that token)
   * @param userPin the token's user PIN, from OpenBao {@code secret/mw-simulators} (key {@code
   *     sim.payshield.pkcs11.user-pin}); never logged, never in a config file
   * @param lmkLabel the LMK's label in the token ({@code lmk})
   */
  public record Pkcs11(String library, int slotListIndex, String userPin, String lmkLabel) {

    public Pkcs11 {
      library = library == null ? "/usr/lib/libp11-kit.so.0" : library;
      userPin = userPin == null ? "" : userPin;
      lmkLabel = lmkLabel == null ? "lmk" : lmkLabel;
    }

    @Override
    public String toString() {
      return "Pkcs11[library=" + library + ", slotListIndex=" + slotListIndex + ", userPin=****, lmkLabel="
          + lmkLabel + "]";
    }
  }
}
