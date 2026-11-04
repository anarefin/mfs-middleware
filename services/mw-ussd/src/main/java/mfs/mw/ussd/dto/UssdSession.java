package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.ussd.util.Sensitive;
import mfs.mw.ussd.util.SensitiveType;

/**
 * Session state kept in Valkey under {@code ussd:{mno}:{sessionId}} (spec 9.2): the node reached,
 * the path taken, the collected inputs, language and menu version. The PIN is never part of it:
 * it is encrypted on receipt and handed straight to the ACTION.
 *
 * @param path the node ids and choices taken so far (the menu path of spec 7.3 rule 1)
 * @param inputs collected inputs by name (e.g. {@code recipient}, {@code amount} in taka)
 * @param invalidAttempts invalid inputs in a row on the current node
 * @param pages the screen pages not yet shown (overflow PAGE)
 * @param endAfterPages true when the last page ends the session
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UssdSession(
    String mno,
    String sessionId,
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    String menuId,
    int menuVersion,
    String language,
    String nodeId,
    List<String> path,
    @Sensitive(SensitiveType.SECRET) Map<String, String> inputs,
    int invalidAttempts,
    @Sensitive(SensitiveType.SECRET) List<String> pages,
    boolean endAfterPages,
    Instant startedAt) {

  public UssdSession {
    path = path == null ? List.of() : List.copyOf(path);
    inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    pages = pages == null ? List.of() : List.copyOf(pages);
  }

  /** At another node (attempts and pages reset). */
  public UssdSession at(String node, List<String> newPath, Map<String, String> newInputs, String lang) {
    return new UssdSession(
        mno, sessionId, msisdn, menuId, menuVersion, lang, node, newPath, newInputs, 0, List.of(), false,
        startedAt);
  }

  public UssdSession withInvalidAttempt() {
    return new UssdSession(
        mno, sessionId, msisdn, menuId, menuVersion, language, nodeId, path, inputs, invalidAttempts + 1,
        List.of(), false, startedAt);
  }

  public UssdSession withPages(List<String> remaining, boolean end) {
    return new UssdSession(
        mno, sessionId, msisdn, menuId, menuVersion, language, nodeId, path, inputs, invalidAttempts,
        remaining, end, startedAt);
  }
}
