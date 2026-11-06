package mfs.mw.seeder.client;

import java.net.URI;

/** A readiness probe (implemented by {@link HttpHealthProbe}). */
@FunctionalInterface
public interface HealthProbe {

  /** True when GET {@code url} answers 200; false on any other status or no answer. */
  boolean ready(URI url);
}
