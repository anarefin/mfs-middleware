package mfs.mw.seeder.dto;

import java.util.Map;
import mfs.mw.seeder.util.Sensitive;
import mfs.mw.seeder.util.SensitiveType;

/**
 * A Keycloak user as the admin REST API lists it: id, username, profile names and the first value
 * of each user-profile attribute (the seeder's users have one value per attribute).
 */
public record KeycloakUser(
    String id,
    String username,
    @Sensitive(SensitiveType.NAME) String firstName,
    @Sensitive(SensitiveType.NAME) String lastName,
    Map<String, String> attributes) {

  public KeycloakUser {
    attributes = attributes == null ? Map.of() : attributes;
  }
}
