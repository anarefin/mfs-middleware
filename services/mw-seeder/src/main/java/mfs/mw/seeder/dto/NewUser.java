package mfs.mw.seeder.dto;

import java.util.Map;
import mfs.mw.seeder.util.Sensitive;
import mfs.mw.seeder.util.SensitiveType;

/**
 * A Keycloak user to create: fake profile values, the realm's test password and, for a realm that
 * demands a code, a TOTP credential with the realm's test secret (HmacSHA256, 6 digits, 30 s, as
 * {@code deploy/keycloak/realms/ops-realm.json} seeds its e2e operators).
 */
public record NewUser(
    String username,
    @Sensitive(SensitiveType.NAME) String firstName,
    @Sensitive(SensitiveType.NAME) String lastName,
    String email,
    Map<String, String> attributes,
    @Sensitive(SensitiveType.SECRET) String password,
    @Sensitive(SensitiveType.SECRET) String totpSecret) {}
