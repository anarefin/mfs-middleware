package mfs.mw.adapter.atm.partner.kamelet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterators;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import mfs.mw.adapter.atm.partner.route.PartnerRecon;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The step of the {@code mw-recon} Kamelet (bean {@code mwRecon}, ticket 86), the last step of a
 * batch partner route: reads the file from its stream line by line (never the whole file in
 * memory) as delimited text and hands the lines to the {@link PartnerRecon.Sink}, which writes them
 * through the outbox in one transaction. The first line names the columns unless the route gives
 * them. Fields may be double-quoted ({@code ""} inside quotes is one quote); blank lines are
 * skipped. A line that cannot be read fails the whole file ({@link BadReconFileException}, naming
 * the line number, never its content: partner data); so does a file without a data line, and one
 * with more data lines than {@code mw.partners.recon.max-lines} (env {@code
 * MW_PARTNER_RECON_MAX_LINES}, default {@value #DEFAULT_MAX_LINES}): every line of a file is written
 * in one transaction, so the cap bounds that transaction. Each refusal is logged ({@code
 * partner.recon.file_refused}, the reason without file content) and the file moves to {@code .error}.
 *
 * <p>Why not {@code split().streaming()}: each split part would be published on its own, so a bad
 * line in the middle would leave the lines before it published and the file in {@code .error}; one
 * stream read inside one transaction keeps a file all or nothing.
 */
@Component("mwRecon")
public class MwReconStep {

  /** Default of {@code mw.partners.recon.max-lines}: data lines per file. */
  public static final int DEFAULT_MAX_LINES = 1_000_000;

  static final int MAX_LINE = 8192;
  static final int MAX_COLUMNS = 50;
  static final int MAX_VALUE = 500;
  private static final Pattern COLUMN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
  private static final Map<String, Character> DELIMITERS = Map.of("comma", ',', "semicolon", ';', "pipe", '|', "tab", '\t');

  private static final Logger log = LoggerFactory.getLogger(MwReconStep.class);

  private final PartnerRecon.Sink sink;
  private final int maxLines;

  public MwReconStep(
      PartnerRecon.Sink sink, @Value("${mw.partners.recon.max-lines:" + DEFAULT_MAX_LINES + "}") int maxLines) {
    if (maxLines < 1) {
      throw new IllegalArgumentException("mw.partners.recon.max-lines must be at least 1");
    }
    this.sink = sink;
    this.maxLines = maxLines;
  }

  public void publish(Exchange exchange, String partnerId, String columns, String delimiter) {
    var message = exchange.getMessage();
    String name = message.getHeader(Exchange.FILE_NAME_ONLY, String.class);
    if (name == null) {
      name = message.getHeader(Exchange.FILE_NAME, String.class);
    }
    String fileId = name + "-" + message.getHeader(Exchange.FILE_LENGTH, String.class) + "-"
        + message.getHeader(Exchange.FILE_LAST_MODIFIED, String.class);
    Character sep = DELIMITERS.get(delimiter == null || delimiter.isEmpty() ? "comma" : delimiter);
    if (sep == null) {
      throw new BadReconFileException("unknown delimiter " + delimiter);
    }
    InputStream in = message.getBody(InputStream.class);
    if (in == null) {
      throw new BadReconFileException("the file has no readable content");
    }
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      Lines lines = new Lines(reader, sep, columns == null || columns.isBlank() ? null : List.of(columns.split(",")), maxLines);
      sink.publish(new PartnerRecon.File(partnerId, name, fileId),
          StreamSupport.stream(Spliterators.spliteratorUnknownSize(lines, 0), false));
      if (lines.count == 0) {
        throw new BadReconFileException("the file has no data line");
      }
    } catch (BadReconFileException e) {
      log.atWarn()
          .addKeyValue("event", "partner.recon.file_refused")
          .addKeyValue("partnerId", partnerId)
          .addKeyValue("fileName", name)
          .addKeyValue("reason", e.getMessage())
          .log("reconciliation file refused; it moves to .error and nothing of it is published");
      throw e;
    } catch (IOException e) {
      throw new UncheckedIOException("the file could not be read", e);
    }
  }

  /** Reads data lines, one ahead so the last is known. */
  private static final class Lines implements Iterator<PartnerRecon.Line> {
    private final BufferedReader reader;
    private final char sep;
    private final int maxLines;
    private List<String> columns;
    private int number;
    private int count;
    /** Data lines read so far (one ahead of {@link #count}). */
    private int data;
    private PartnerRecon.Line next;
    private boolean started;

    Lines(BufferedReader reader, char sep, List<String> columns, int maxLines) {
      this.reader = reader;
      this.sep = sep;
      this.columns = columns;
      this.maxLines = maxLines;
    }

    @Override
    public boolean hasNext() {
      if (!started) {
        started = true;
        next = read();
      }
      return next != null;
    }

    @Override
    public PartnerRecon.Line next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      PartnerRecon.Line current = next;
      next = read();
      count++;
      return next == null ? new PartnerRecon.Line(current.lineNumber(), true, current.fields()) : current;
    }

    private PartnerRecon.Line read() {
      try {
        String line;
        while ((line = reader.readLine()) != null) {
          number++;
          if (line.length() > MAX_LINE) {
            throw new BadReconFileException("line " + number + " is longer than " + MAX_LINE + " characters");
          }
          if (line.isBlank()) {
            continue;
          }
          List<String> values = split(line, sep, number);
          if (columns == null) {
            if (values.size() > MAX_COLUMNS || values.stream().anyMatch(c -> !COLUMN.matcher(c.trim()).matches())) {
              throw new BadReconFileException("line " + number + " (the header) must name at most " + MAX_COLUMNS
                  + " columns: letters, digits, underscore");
            }
            columns = values.stream().map(String::trim).toList();
            continue;
          }
          if (data == maxLines) {
            throw new BadReconFileException("the file has more than " + maxLines + " data lines");
          }
          data++;
          if (values.size() != columns.size()) {
            throw new BadReconFileException("line " + number + " has " + values.size() + " fields, expected " + columns.size());
          }
          Map<String, String> fields = new LinkedHashMap<>();
          for (int i = 0; i < values.size(); i++) {
            if (values.get(i).length() > MAX_VALUE) {
              throw new BadReconFileException("line " + number + " field " + columns.get(i) + " is longer than " + MAX_VALUE);
            }
            fields.put(columns.get(i), values.get(i));
          }
          return new PartnerRecon.Line(number, false, fields);
        }
        return null;
      } catch (IOException e) {
        throw new UncheckedIOException("the file could not be read at line " + number, e);
      }
    }
  }

  static List<String> split(String line, char sep, int number) {
    List<String> out = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          field.append('"');
          i++;
        } else if (c == '"') {
          quoted = false;
        } else {
          field.append(c);
        }
      } else if (c == '"' && field.isEmpty()) {
        quoted = true;
      } else if (c == sep) {
        out.add(field.toString());
        field.setLength(0);
      } else {
        field.append(c);
      }
    }
    if (quoted) {
      throw new BadReconFileException("line " + number + " has an unterminated quote");
    }
    out.add(field.toString());
    return out;
  }

  /** The file cannot be read as the route says; the message names lines, never their content. */
  public static final class BadReconFileException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BadReconFileException(String message) {
      super(message);
    }
  }
}
