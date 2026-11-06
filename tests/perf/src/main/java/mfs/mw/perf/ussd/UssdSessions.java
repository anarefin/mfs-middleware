package mfs.mw.perf.ussd;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * USSD sessions played by the USSD gateway simulator in HTTP mode ({@code POST
 * /test/sim/ussdgw/sessions}, test-only route): the simulator plays MNO robi and POSTs every step
 * through APISIX ({@code /ussd/robi}) to mw-ussd, as in the API suite folder 18 (tests/api).
 */
public final class UssdSessions {

  public static final String PATH = "test/sim/ussdgw/sessions";
  /** The English success screen of a USSD P2P (mw-ussd menu main-v2.json). */
  private static final String SENT = "Tk 10.00 sent to ";
  private static final Pattern TXN_ID = Pattern.compile("TxnID (\\S+)");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private UssdSessions() {}

  /** A P2P of 10 taka from {@code msisdn} to {@code toWallet}, menu in English. */
  public static String p2p(String msisdn, String toWallet, String pin) {
    ObjectNode b = JSON.createObjectNode();
    b.put("mno", "robi").put("msisdn", msisdn).put("serviceCode", "*268#");
    b.putArray("inputs").add("9").add("1").add(toWallet).add("10").add("1").add(pin);
    return JSON.writeValueAsString(b);
  }

  /** Whether the session's last screen says the money was sent. */
  public static boolean sent(String lastScreen) {
    return lastScreen != null && lastScreen.startsWith(SENT);
  }

  /** The txnId on the last screen, or null. */
  public static String txnId(String lastScreen) {
    if (lastScreen == null) {
      return null;
    }
    Matcher m = TXN_ID.matcher(lastScreen);
    return m.find() ? m.group(1) : null;
  }
}
