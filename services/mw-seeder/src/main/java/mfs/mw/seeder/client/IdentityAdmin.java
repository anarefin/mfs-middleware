package mfs.mw.seeder.client;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import mfs.mw.seeder.dto.KeycloakUser;
import mfs.mw.seeder.dto.NewUser;

/** The Keycloak admin REST calls seed-identities needs (implemented by {@link KeycloakAdminClient}). */
public interface IdentityAdmin {

  /** The user with exactly this username (profile and attributes), if any. */
  Optional<KeycloakUser> findUser(String realm, String username);

  /** The id of the user with exactly this username, if any. */
  default Optional<String> findUserId(String realm, String username) {
    return findUser(realm, username).map(KeycloakUser::id);
  }

  /** Creates an enabled user with its credentials; returns its id. */
  String createUser(String realm, NewUser user);

  /** The user's directly mapped realm roles. */
  Set<String> realmRoles(String realm, String userId);

  void addRealmRoles(String realm, String userId, Collection<String> roles);

  boolean clientExists(String realm, String clientId);
}
