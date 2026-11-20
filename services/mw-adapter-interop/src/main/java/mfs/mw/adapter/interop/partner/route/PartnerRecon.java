package mfs.mw.adapter.interop.partner.route;

import java.util.Map;
import java.util.stream.Stream;

/**
 * A partner's reconciliation or settlement file read by a batch route (ticket 86): the file and its
 * data lines, handed by the {@code mw-recon} Kamelet to the service's {@link Sink} ({@code
 * messaging/PartnerReconPublisher}, {@code mw.partner.recon.v1} through the outbox). The lines carry
 * the partner's data: never logged.
 */
public final class PartnerRecon {

  private PartnerRecon() {}

  /**
   * @param partnerId the batch route's partner
   * @param fileName the file's name on the partner host
   * @param fileId {@code <fileName>-<size>-<modified epoch ms>}, the same for every line
   */
  public record File(String partnerId, String fileName, String fileId) {}

  /**
   * @param lineNumber 1-based line of the file
   * @param last true on the file's last data line
   * @param fields column name -> value, as text
   */
  public record Line(int lineNumber, boolean last, Map<String, String> fields) {
    public Line {
      fields = Map.copyOf(fields);
    }
  }

  /** Where the lines of a file go. */
  @FunctionalInterface
  public interface Sink {
    /**
     * Publishes every line of {@code file} in one transaction: all or none. {@code lines} is read
     * lazily from the file; an exception it throws rolls the whole file back.
     */
    void publish(File file, Stream<Line> lines);
  }
}
