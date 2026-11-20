package mfs.mw.admin.api.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.ChannelClientSpec;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.entity.ChannelClient;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.repository.ChannelClientRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Approving a {@code channel_client} change request (ticket 100a), inside the decision
 * transaction: the row is locked and must still be at the version the maker saw ({@code
 * payload.baseVersion}; absent: the client must still not exist), else 409 MW-VAL-030 and the
 * request stays PENDING; the full definition ({@code payload.client}) is stored as the next version.
 * The APISIX projection runs only AFTER that transaction commits ({@link
 * ChannelClientProjection#project}): APISIX never holds a client the registry does not, and a
 * failed projection is repaired by the reconcile job.
 */
@Component
public class ChannelClientChangeHandler implements ResourceChangeHandler {

  /** {@code after.sentAs} of the approval audit. */
  static final String SENT_AS = "apisix";

  private final ChannelClientRepository clients;
  private final ChannelClientProjection projection;
  private final JsonMapper json;

  public ChannelClientChangeHandler(ChannelClientRepository clients, ChannelClientProjection projection, JsonMapper json) {
    this.clients = clients;
    this.projection = projection;
    this.json = json;
  }

  @Override
  public String resourceType() {
    return ChannelClient.RESOURCE_TYPE;
  }

  @Override
  public Set<AdminRole> checkers() {
    return AdminRole.CHANNEL_CLIENT_CHECKER;
  }

  @Override
  public Approval prepare(ChangeRequest cr, Operator checker, Instant now, String correlationId) {
    String id = cr.resourceId();
    Long baseVersion = cr.payload().get("baseVersion") instanceof Number n ? n.longValue() : null;
    ChannelClientSpec spec = json.convertValue(cr.payload().get("client"), ChannelClientSpec.class);
    return new Approval(
        cr.payload(),
        SENT_AS,
        () -> {
          Optional<ChannelClient> held = clients.findForUpdate(id);
          long version;
          if (baseVersion == null) {
            version = 1;
            if (held.isPresent() || !clients.insert(new ChannelClient(id, spec, version, now, cr.makerId(), checker.username()))) {
              throw moved();
            }
          } else {
            if (held.isEmpty() || held.get().version() != baseVersion) {
              throw moved();
            }
            version = baseVersion + 1;
            if (!clients.update(new ChannelClient(id, spec, version, now, cr.makerId(), checker.username()), baseVersion)) {
              throw moved();
            }
          }
          TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              projection.project(id);
            }
          });
          return Map.of("version", version);
        });
  }

  private static MwException moved() {
    return new MwException(ErrorCodes.VAL_ACTION_NOT_ALLOWED, "The channel client changed after this request was made")
        .withStatus(HttpStatus.CONFLICT);
  }
}
