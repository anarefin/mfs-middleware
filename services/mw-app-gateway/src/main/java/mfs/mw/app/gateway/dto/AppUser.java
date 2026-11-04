package mfs.mw.app.gateway.dto;

import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * The authenticated app user, taken from the Keycloak token: the wallet MSISDN (claim {@code
 * msisdn}), the channel and the wallet type that follow from the realm role.
 */
public record AppUser(
    @Sensitive(SensitiveType.MSISDN) String msisdn, String channel, String walletType) {}
