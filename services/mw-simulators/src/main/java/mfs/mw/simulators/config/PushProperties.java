package mfs.mw.simulators.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The push server mock {@code push} (ticket 110), {@code sim.push}.
 *
 * @param apiKey the key mw-notify must send ({@code Authorization: key=<apiKey>}); stack: {@code
 *     PUSH_API_KEY} in Vault {@code secret/mw-simulators}; never in the main config; unset = every push
 *     refused
 */
@ConfigurationProperties("sim.push")
public record PushProperties(String apiKey) {}
