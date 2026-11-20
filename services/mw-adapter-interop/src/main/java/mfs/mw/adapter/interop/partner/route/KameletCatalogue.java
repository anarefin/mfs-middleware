package mfs.mw.adapter.interop.partner.route;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The shipped Kamelet library ({@code classpath:kamelets/mw-*.kamelet.yaml}, ADR D28): the only
 * Kamelets a partner route may call. A breaking change to a Kamelet ships under a new name ({@code
 * mw-http-call-v2}); the old file stays until no LIVE route uses it.
 */
@Component
public class KameletCatalogue {

  public static final String LOCATION = "classpath*:kamelets/mw-*.kamelet.yaml";

  private final Set<String> names;

  public KameletCatalogue() {
    Set<String> found = new TreeSet<>();
    try {
      for (Resource r : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
        String file = r.getFilename();
        if (file != null) {
          found.add(file.substring(0, file.length() - ".kamelet.yaml".length()));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list the Kamelet library", e);
    }
    this.names = Set.copyOf(found);
  }

  /** Every Kamelet name of the library. */
  public Set<String> names() {
    return names;
  }

  public boolean contains(String name) {
    return names.contains(name);
  }
}
