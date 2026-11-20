package mfs.mw.admin.api.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The part of the APISIX Admin API the channel client projection uses (ticket 100a): consumers and
 * consumer groups, read, written (PUT with a fixed id: idempotent) and deleted. Implemented by
 * {@link ApisixAdminClient}; failures are {@code exception/ApisixUnavailableException}.
 */
public interface ApisixAdmin {

  /** The object kinds, by their Admin API path segment. */
  enum Kind {
    CONSUMERS("consumers"),
    CONSUMER_GROUPS("consumer_groups"),
    /** Routes an OAUTH2_CC client is allowed to call (ticket 100b): the projection merges plugins, it does not create the route. */
    ROUTES("routes");

    private final String path;

    Kind(String path) {
      this.path = path;
    }

    public String path() {
      return path;
    }
  }

  /** The stored object ({@code value} of the Admin API answer), empty when there is none. */
  Optional<Map<String, Object>> get(Kind kind, String id);

  void put(Kind kind, String id, Map<String, Object> body);

  /** Deletes the object; one that does not exist is fine. */
  void delete(Kind kind, String id);

  /** Every object of the kind. */
  List<Map<String, Object>> list(Kind kind);
}
