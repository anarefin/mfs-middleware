package mfs.mw.adapter.interop.dto.iso;

import mfs.mw.adapter.interop.util.Sensitive;
import mfs.mw.adapter.interop.util.SensitiveType;

/**
 * One side of an NPSB transfer (ticket 121b): the holder's name, the account (wallet MSISDN or bank
 * account, {@code Othr/Id}) and the participant that holds it ({@code FinInstnId/BICFI}).
 */
public record NpsbParty(
    @Sensitive(SensitiveType.NAME) String name,
    @Sensitive(SensitiveType.ACCOUNT) String account,
    String agent) {}
