package mfs.mw.ussd.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.ussd.billpay.*}: mw-adapter-billpay's internal API (biller catalogue, bill fetch;
 * read-only, spec 9.2 DYNAMIC billers). The read timeout is short: the MNO holds the USSD step
 * open while the bill is fetched.
 *
 * @param catalogueTtl how long the biller list is reused (also keeps the numbering of one session
 *     stable between the screen and the choice)
 */
@Validated
@ConfigurationProperties("mw.ussd.billpay")
public record BillPayProperties(
    @NotNull URI baseUrl,
    @NotNull Duration connectTimeout,
    @NotNull Duration readTimeout,
    @NotNull Duration catalogueTtl) {}
