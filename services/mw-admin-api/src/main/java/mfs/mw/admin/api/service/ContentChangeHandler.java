package mfs.mw.admin.api.service;

import java.time.Instant;
import java.util.Set;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.security.AdminRole;

/**
 * The {@link ResourceChangeHandler} of a content resource type (ticket 39): an approval publishes
 * the PENDING version through {@link ContentChangeService#publish} inside the decision
 * transaction; a rejection or expiry marks it. {@link UssdMenuChangeHandler}, {@link
 * TemplateChangeHandler}.
 */
abstract class ContentChangeHandler implements ResourceChangeHandler {

  private final ContentChangeService content;

  ContentChangeHandler(ContentChangeService content) {
    this.content = content;
  }

  @Override
  public Set<AdminRole> checkers() {
    return AdminRole.CONTENT_CHECKER;
  }

  @Override
  public Approval prepare(ChangeRequest cr, Operator checker, Instant now, String correlationId) {
    return new Approval(
        cr.payload(), ContentChangeService.CONFIG_TOPIC, () -> content.publish(cr, checker.username(), correlationId, now));
  }

  @Override
  public void closed(ChangeRequest cr, ChangeRequest.Status status, String checkerId, Instant now) {
    content.decided(cr, status, checkerId, now);
  }
}
