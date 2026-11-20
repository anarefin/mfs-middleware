package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mfs.mw.ussd.util.Sensitive;
import mfs.mw.ussd.util.SensitiveType;

/**
 * Session state kept in Redis under {@code ussd:{mno}:{sessionId}} (spec 9.2): the node reached,
 * the path taken, the collected inputs, language and menu version. The PIN is never part of it:
 * it is encrypted on receipt and handed straight to the ACTION.
 *
 * @param path the node ids and choices taken so far (the menu path of spec 7.3 rule 1)
 * @param inputs collected inputs by name (e.g. {@code recipient}, {@code amount} in taka)
 * @param invalidAttempts invalid inputs in a row on the current node
 * @param pages every page of the current screen (empty when it is not paged); {@code pageIndex} is
 *     the page on the handset
 * @param endAfterPages true when the last page ends the session
 * @param screenAt when the current screen was shown; the per-screen timeout is measured from here
 * @param back node ids to return to with {@code 00} (the newest last)
 * @param pageIndex the page of {@code pages} currently shown
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
    Instant startedAt,
    Instant screenAt,
    List<String> back,
    int pageIndex) {

  /** A session from before the screen clock and the back stack: those start empty, at {@code startedAt}. */
  public UssdSession(
      String mno,
      String sessionId,
      String msisdn,
      String menuId,
      int menuVersion,
      String language,
      String nodeId,
      List<String> path,
      Map<String, String> inputs,
      int invalidAttempts,
      List<String> pages,
      boolean endAfterPages,
      Instant startedAt) {
    this(
        mno, sessionId, msisdn, menuId, menuVersion, language, nodeId, path, inputs, invalidAttempts, pages,
        endAfterPages, startedAt, startedAt, List.of(), 0);
  }

  public UssdSession {
    path = path == null ? List.of() : List.copyOf(path);
    inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    pages = pages == null ? List.of() : List.copyOf(pages);
    back = back == null ? List.of() : List.copyOf(back);
    if (screenAt == null) {
      screenAt = startedAt;
    }
  }

  /** At another node (attempts and pages reset). */
  public UssdSession at(String node, List<String> newPath, Map<String, String> newInputs, String lang) {
    return new UssdSession(
        mno, sessionId, msisdn, menuId, menuVersion, lang, node, newPath, newInputs, 0, List.of(), false,
        startedAt, screenAt, back, 0);
  }

  /**
   * Moves to {@code node}, pushing the node just left onto the back stack when it changes. Pages
   * start again.
   */
  public UssdSession forward(String node, List<String> newPath, Map<String, String> newInputs, String lang) {
    List<String> stack = back;
    if (!node.equals(nodeId)) {
      ArrayList<String> copy = new ArrayList<>(back);
      copy.add(nodeId);
      stack = List.copyOf(copy);
    }
    return new UssdSession(
        mno, sessionId, msisdn, menuId, menuVersion, lang, node, newPath, newInputs, 0, List.of(), false,
        startedAt, screenAt, stack, 0);
  }

  public UssdSession withInvalidAttempt() {
    return new UssdSession(
        mno, sessionId, msisdn, menuId, menuVersion, language, nodeId, path, inputs, invalidAttempts + 1,
        List.of(), false, startedAt, screenAt, back, 0);
  }

  public UssdSession withPages(List<String> allPages, boolean end) {
    return new UssdSession(
        mno, sessionId, msisdn, menuId, menuVersion, language, nodeId, path, inputs, invalidAttempts,
        allPages, end, startedAt, screenAt, back, pageIndex);
  }

  public UssdSession atPage(int index) {
    return new UssdSession(
        mno, sessionId, msisdn, menuId, menuVersion, language, nodeId, path, inputs, invalidAttempts,
        pages, endAfterPages, startedAt, screenAt, back, index);
  }

  public UssdSession shown(Instant at) {
    return new UssdSession(
        mno, sessionId, msisdn, menuId, menuVersion, language, nodeId, path, inputs, invalidAttempts,
        pages, endAfterPages, startedAt, at, back, pageIndex);
  }
}
