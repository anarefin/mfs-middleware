package mfs.mw.seeder.client;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import mfs.mw.seeder.dto.NewUser;

/** The Keycloak admin REST calls seed-identities needs (implemented by {@link KeycloakAdminClient}). */
public interface IdentityAdmin {

  /** The id of the user with exactly this username, if any. */
  Optional<String> findUserId(String realm, String username);

  /** Creates an enabled user with its credentials; returns its id. */
  String createUser(String realm, NewUser user);

  /** The user's directly mapped realm roles. */
  Set<String> realmRoles(String realm, String userId);

  void addRealmRoles(String realm, String userId, Collection<String> roles);

  boolean clientExists(String realm, String clientId);
}
