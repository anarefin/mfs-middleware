package mfs.mw.seeder.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.seeder.client.AdminConsole;
import mfs.mw.seeder.dto.ChangeRequestView;
import mfs.mw.seeder.dto.ContentVersionView;
import mfs.mw.seeder.dto.MenuProposal;
import mfs.mw.seeder.dto.PartnerChangeRequest;
import mfs.mw.seeder.dto.PartnerView;
import mfs.mw.seeder.dto.TemplateProposal;
import mfs.mw.seeder.exception.RemoteCallException;

/**
 * In-memory mw-admin-api with its maker-checker rules: one open request per resource (409), a
 * change that changes nothing (409), the maker never approves (403), approval applies the change.
 */
final class FakeAdminConsole implements AdminConsole {

  record Cr(String id, String type, String resource, String maker, Object body, String[] status) {}

  final Map<String, PartnerView> partners = new LinkedHashMap<>();
  final Map<String, Cr> requests = new LinkedHashMap<>();
  /** configKey -> versions, newest first. */
  final Map<String, List<ContentVersionView>> versions = new LinkedHashMap<>();
  final List<String> writes = new ArrayList<>();

  @Override
  public Optional<PartnerView> partner(String asUser, String partnerId) {
    return Optional.ofNullable(partners.get(partnerId));
  }

  @Override
  public ChangeRequestView requestPartnerChange(String asUser, String partnerId, PartnerChangeRequest req) {
    PartnerView p = partners.get(partnerId);
    if (p == null) {
      throw new RemoteCallException(404, "no partner");
    }
    openCheck("partner", partnerId);
    boolean noop =
        switch (req.action()) {
          case "ENABLE" -> p.config().isEnabled();
          case "DISABLE" -> !p.config().isEnabled();
          case "SET_TIMEOUTS" -> req.timeouts().timeoutMs().equals(p.config().timeoutMs());
          default -> throw new IllegalArgumentException(req.action());
        };
    if (noop) {
      throw new RemoteCallException(409, "nothing changes");
    }
    return add("partner", partnerId, asUser, req);
  }

  @Override
  public List<ChangeRequestView> pendingRequests(String asUser, String resourceType, String resourceId) {
    return requests.values().stream()
        .filter(c -> c.type().equals(resourceType) && c.resource().equals(resourceId) && c.status()[0].equals("PENDING"))
        .map(FakeAdminConsole::view)
        .toList();
  }

  @Override
  public ChangeRequestView approve(String asUser, String id) {
    Cr c = requests.get(id);
    if (c.maker().equals(asUser)) {
      throw new RemoteCallException(403, "maker cannot approve");
    }
    writes.add("approve " + c.type() + "/" + c.resource());
    if (c.body() instanceof PartnerChangeRequest req) {
      PartnerView p = partners.get(c.resource());
      PartnerView.Config cfg =
          switch (req.action()) {
            case "ENABLE" -> new PartnerView.Config(true, p.config().timeoutMs());
            case "DISABLE" -> new PartnerView.Config(false, p.config().timeoutMs());
            default -> new PartnerView.Config(p.config().enabled(), req.timeouts().timeoutMs());
          };
      partners.put(c.resource(), new PartnerView(p.partnerId(), p.family(), p.version() + 1, cfg));
    } else {
      List<ContentVersionView> vs = versions.get(key(c));
      ContentVersionView v = vs.getFirst();
      vs.set(0, new ContentVersionView(v.configKey(), v.version(), "PUBLISHED", v.changeRequestId(), v.makerId()));
    }
    c.status()[0] = "APPROVED";
    return view(c);
  }

  @Override
  public List<ContentVersionView> menuVersions(String asUser, String menuId) {
    return List.copyOf(versions.getOrDefault("ussd-menu/" + menuId, List.of()));
  }

  @Override
  public ChangeRequestView proposeMenu(String asUser, String menuId, MenuProposal proposal) {
    openCheck("ussd_menu", menuId);
    return addVersion("ussd_menu", menuId, "ussd-menu/" + menuId, asUser, proposal);
  }

  @Override
  public List<ContentVersionView> templateVersions(String asUser, String code, String channel, String locale) {
    return List.copyOf(versions.getOrDefault("template/" + code + "/" + channel + "/" + locale, List.of()));
  }

  @Override
  public ChangeRequestView proposeTemplate(String asUser, String code, String channel, String locale, TemplateProposal p) {
    String rid = code + "/" + channel + "/" + locale;
    openCheck("notify_template", rid);
    return addVersion("notify_template", rid, "template/" + rid, asUser, p);
  }

  /** A version some earlier run or operator left in {@code status}. */
  void putVersion(String configKey, int version, String status, String maker) {
    String type = configKey.startsWith("ussd-menu/") ? "ussd_menu" : "notify_template";
    String resource = configKey.substring(configKey.indexOf('/') + 1);
    Cr c = new Cr(UUID.randomUUID().toString(), type, resource, maker, "seeded", new String[] {status.equals("PUBLISHED") ? "APPROVED" : status});
    requests.put(c.id(), c);
    versions.computeIfAbsent(configKey, k -> new ArrayList<>()).addFirst(new ContentVersionView(configKey, version, status, c.id(), maker));
  }

  private ChangeRequestView addVersion(String type, String rid, String configKey, String maker, Object body) {
    ChangeRequestView cr = add(type, rid, maker, body);
    List<ContentVersionView> vs = versions.computeIfAbsent(configKey, k -> new ArrayList<>());
    int next = vs.isEmpty() ? 1 : vs.getFirst().version() + 1;
    vs.addFirst(new ContentVersionView(configKey, next, "PENDING", cr.id(), maker));
    return cr;
  }

  private void openCheck(String type, String rid) {
    if (!pendingRequests("x", type, rid).isEmpty()) {
      throw new RemoteCallException(409, "one open request per resource");
    }
  }

  private ChangeRequestView add(String type, String rid, String maker, Object body) {
    Cr c = new Cr(UUID.randomUUID().toString(), type, rid, maker, body, new String[] {"PENDING"});
    requests.put(c.id(), c);
    writes.add("request " + type + "/" + rid + " " + (body instanceof PartnerChangeRequest r ? r.action() : "PUBLISH"));
    return view(c);
  }

  private String key(Cr c) {
    return (c.type().equals("ussd_menu") ? "ussd-menu/" : "template/") + c.resource();
  }

  private static ChangeRequestView view(Cr c) {
    return new ChangeRequestView(c.id(), c.type(), c.resource(), "X", c.status()[0], c.maker(), null);
  }
}
