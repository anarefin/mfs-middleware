package mfs.mw.ussd.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.ConfigChangedEvent;
import mfs.mw.ussd.mapper.MenuParser;
import mfs.mw.ussd.repository.MenuRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Takes a USSD menu version the admin console published (ticket 39, spec 9.2, 20.4) from {@code
 * mw.config.changed.v1} (configType USSD_MENU, value {@code {menuId, version, menu, activations:
 * [{mno, goLiveAt}]}}) and stores it, in one transaction, as a new immutable version of the menu
 * with one activation per configured MNO at its go-live. {@link MenuService} serves it to sessions
 * that start after the go-live; a running session keeps the version it started on.
 *
 * <ul>
 *   <li>The row's own version is numbered here (above every stored version, at least {@link
 *       #FIRST_PUBLISHED}); the admin version is kept as {@code config_version}, so a redelivered
 *       or replayed record is stored once and an older one is ignored.
 *   <li>The tree is checked again ({@link MenuValidator#publishProblems}); an unusable one is
 *       logged ({@code ussd.menu.rejected}) and dropped, never retried: the same record cannot get
 *       better. An MNO not configured here is skipped.
 *   <li>Other config types, DELETE and tombstones are not this service's and are ignored.
 * </ul>
 */
@Service
public class MenuPublisher {

  private static final Logger log = LoggerFactory.getLogger(MenuPublisher.class);

  /** Versions published by the admin console start here; shipped seeds stay below. */
  static final int FIRST_PUBLISHED = 100;

  private final MenuRepository menus;
  private final UssdProperties props;
  private final JsonMapper json;
  private final TransactionTemplate tx;

  public MenuPublisher(MenuRepository menus, UssdProperties props, JsonMapper json, TransactionTemplate tx) {
    this.menus = menus;
    this.props = props;
    this.json = json;
    this.tx = tx;
  }

  public void onConfigChanged(ConfigChangedEvent e) {
    if (e == null
        || e.configType() != ConfigChangedEvent.ConfigType.USSD_MENU
        || e.change() != ConfigChangedEvent.Change.UPSERT
        || e.value() == null) {
      return;
    }
    Object menuId = e.value().get("menuId");
    if (!(menuId instanceof String id) || id.isBlank() || !(e.value().get("menu") instanceof Map<?, ?> tree)) {
      reject(e, "value without menuId or menu");
      return;
    }
    tx.executeWithoutResult(s -> store(e, id, tree));
  }

  private void store(ConfigChangedEvent e, String menuId, Map<?, ?> rawTree) {
    long configVersion = e.version();
    Long latest = menus.latestConfigVersion(menuId).orElse(null);
    if (latest != null && latest >= configVersion) {
      log.atInfo()
          .addKeyValue("event", "ussd.menu.publish_skipped")
          .addKeyValue("menuId", menuId)
          .addKeyValue("configVersion", configVersion)
          .log("menu version already stored or older than the stored one");
      return;
    }
    int version = menus.nextPublishedVersion(menuId, FIRST_PUBLISHED);
    Map<String, Object> tree = new LinkedHashMap<>();
    rawTree.forEach((k, v) -> tree.put(String.valueOf(k), v));
    tree.put("menuId", menuId);
    tree.put("version", version);
    String treeJson = json.writeValueAsString(tree);
    List<String> problems;
    try {
      problems = MenuValidator.publishProblems(MenuParser.parse(json, treeJson));
    } catch (RuntimeException ex) {
      problems = List.of("not a menu tree");
    }
    if (!problems.isEmpty()) {
      reject(e, problems.size() + " problem(s): " + problems);
      return;
    }
    menus.insertPublished(menuId, version, configVersion, treeJson);
    for (Object o : e.value().get("activations") instanceof List<?> l ? l : List.of()) {
      if (!(o instanceof Map<?, ?> a) || !(a.get("mno") instanceof String mno)) {
        continue;
      }
      if (!props.mno().containsKey(mno)) {
        log.atWarn()
            .addKeyValue("event", "ussd.menu.activation_skipped")
            .addKeyValue("menuId", menuId)
            .addKeyValue("mno", mno)
            .log("published menu names an MNO not configured here");
        continue;
      }
      Instant goLive = a.get("goLiveAt") == null ? e.changedAt() : Instant.parse(String.valueOf(a.get("goLiveAt")));
      menus.activate(mno, menuId, version, goLive);
      log.atInfo()
          .addKeyValue("event", "ussd.menu.activated")
          .addKeyValue("mno", mno)
          .addKeyValue("menuId", menuId)
          .addKeyValue("menuVersion", version)
          .addKeyValue("configVersion", configVersion)
          .addKeyValue("goLiveAt", goLive.toString())
          .log("published menu scheduled for MNO");
    }
    log.atInfo()
        .addKeyValue("event", "ussd.menu.published")
        .addKeyValue("menuId", menuId)
        .addKeyValue("menuVersion", version)
        .addKeyValue("configVersion", configVersion)
        .log("published menu version stored");
  }

  private static void reject(ConfigChangedEvent e, String why) {
    log.atError()
        .addKeyValue("event", "ussd.menu.rejected")
        .addKeyValue("configKey", e.configKey())
        .addKeyValue("configVersion", e.version())
        .log("published menu not usable, dropped: {}", why);
  }
}
