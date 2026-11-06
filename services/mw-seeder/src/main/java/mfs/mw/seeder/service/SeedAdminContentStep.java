package mfs.mw.seeder.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import mfs.mw.seeder.client.AdminConsole;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.config.SeederProperties.MenuSpec;
import mfs.mw.seeder.config.SeederProperties.TemplateSpec;
import mfs.mw.seeder.dto.ChangeRequestView;
import mfs.mw.seeder.dto.ContentVersionView;
import mfs.mw.seeder.dto.MenuProposal;
import mfs.mw.seeder.dto.SeedManifest;
import mfs.mw.seeder.dto.TemplateProposal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * seed-admin-content: the USSD menus and notification templates of {@code mw.seeder.content}
 * published through mw-admin-api (maker proposes, checker approves; mw-ussd and mw-notify take the
 * version from {@code mw.config.changed.v1}). The values are copies of what those services ship
 * (mw-ussd's {@code menus/main-v2.json}, mw-notify's V5 template rows), so publishing them changes
 * no text a test reads; it gives the console and the suites a first admin-made version. Content
 * with any PUBLISHED admin version is left alone (whoever published it); a version the seed maker
 * left PENDING is approved; one another operator left PENDING fails the step.
 */
@Service
@Order(5)
public class SeedAdminContentStep implements SeedStep {

  static final String REASON = "seed: first admin version (mw-seeder)";

  private static final Logger log = LoggerFactory.getLogger(SeedAdminContentStep.class);

  private final AdminConsole admin;
  private final MakerChecker ops;
  private final SeederProperties props;
  private final ResourceLoader resources;
  private final JsonMapper json;

  public SeedAdminContentStep(AdminConsole admin, SeederProperties props, ResourceLoader resources, JsonMapper json) {
    this.admin = admin;
    this.ops = new MakerChecker(admin, props.operators());
    this.props = props;
    this.resources = resources;
    this.json = json;
  }

  @Override
  public String id() {
    return "seed-admin-content";
  }

  @Override
  public StepResult run(SeedContext ctx) {
    StepResult result = StepResult.unchanged(0);
    List<SeedManifest.UssdMenu> menus = new ArrayList<>();
    List<SeedManifest.Template> templates = new ArrayList<>();
    for (MenuSpec m : props.content().menus()) {
      Seeded s =
          ensure(
              "ussd_menu", m.menuId(),
              () -> admin.menuVersions(ops.checker(), m.menuId()),
              () -> admin.proposeMenu(ops.maker(), m.menuId(), menuProposal(m)));
      result = result.plus(s.result());
      menus.add(new SeedManifest.UssdMenu(m.menuId(), s.version(), m.mnos()));
    }
    for (TemplateSpec t : props.content().templates()) {
      String rid = t.code() + "/" + t.channel() + "/" + t.locale();
      Seeded s =
          ensure(
              "notify_template", rid,
              () -> admin.templateVersions(ops.checker(), t.code(), t.channel(), t.locale()),
              () ->
                  admin.proposeTemplate(
                      ops.maker(), t.code(), t.channel(), t.locale(),
                      new TemplateProposal(t.body(), t.subject(), t.senderId(), REASON)));
      result = result.plus(s.result());
      templates.add(new SeedManifest.Template(t.code(), t.channel(), t.locale(), s.version()));
    }
    ctx.menus().addAll(menus);
    ctx.templates().addAll(templates);
    return result;
  }

  private record Seeded(StepResult result, int version) {}

  private Seeded ensure(
      String type, String resourceId, Supplier<List<ContentVersionView>> versions, Supplier<ChangeRequestView> propose) {
    List<ContentVersionView> have = versions.get();
    Optional<ContentVersionView> published = have.stream().filter(v -> "PUBLISHED".equals(v.status())).findFirst();
    if (published.isPresent()) {
      return new Seeded(StepResult.unchanged(1), published.get().version());
    }
    List<ChangeRequestView> pending =
        have.stream()
            .filter(v -> "PENDING".equals(v.status()))
            .map(v -> new ChangeRequestView(v.changeRequestId(), type, resourceId, "PUBLISH", "PENDING", v.makerId(), null))
            .toList();
    if (ops.approveLeftovers(pending, type + " " + resourceId) == 0) {
      ops.approve(propose.get());
    }
    int version =
        versions.get().stream()
            .filter(v -> "PUBLISHED".equals(v.status()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(type + " " + resourceId + " has no published version after approval"))
            .version();
    log.atInfo()
        .addKeyValue("event", "seed.content.published")
        .log("{} {} published as admin version {}", type, resourceId, version);
    return new Seeded(StepResult.created(1), version);
  }

  private MenuProposal menuProposal(MenuSpec m) {
    ObjectNode tree;
    try (InputStream in = resources.getResource(m.resource()).getInputStream()) {
      tree = (ObjectNode) json.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read menu " + m.resource(), e);
    }
    // menuId and version inside the tree are the admin's to set
    tree.remove("menuId");
    tree.remove("version");
    return new MenuProposal(tree, m.mnos().stream().map(MenuProposal.Activation::new).toList(), REASON);
  }
}
