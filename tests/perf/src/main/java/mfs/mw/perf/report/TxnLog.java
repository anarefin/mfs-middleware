package mfs.mw.perf.report;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The run's transaction log ({@code txns.csv} in the run directory), appended to by the
 * simulation's virtual users (one line per transaction, {@link TxnRecord#toCsv}) and read by {@link
 * OverheadReport}. Also writes the run's {@code run.properties} ({@link RunWindow}).
 */
public final class TxnLog implements AutoCloseable {

  private final Path dir;
  private final BufferedWriter out;

  public TxnLog(Path dir) {
    this.dir = dir;
    try {
      Files.createDirectories(dir);
      this.out = Files.newBufferedWriter(dir.resolve("txns.csv"), StandardCharsets.UTF_8);
      out.write(TxnRecord.HEADER);
      out.newLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public synchronized void append(TxnRecord r) {
    try {
      out.write(r.toCsv());
      out.newLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void window(RunWindow w) {
    try (Writer o = Files.newBufferedWriter(dir.resolve("run.properties"), StandardCharsets.UTF_8)) {
      w.toProperties().store(o, "steady window of the last perf run");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public synchronized void close() {
    try {
      out.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
