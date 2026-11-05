package mfs.mw.portal.api.dto;

import mfs.mw.portal.api.util.Sensitive;
import mfs.mw.portal.api.util.SensitiveType;

/**
 * One valid row of a bulk disbursement file (spec 9.4): its line in the file (header = 1), the
 * payee wallet in canonical {@code 01XNNNNNNNN} form, the amount in poisha and the org's payment
 * reference (unique within the file).
 */
public record BulkRow(
    int line, @Sensitive(SensitiveType.MSISDN) String msisdn, long amountMinor, String reference) {}
