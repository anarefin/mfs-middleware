package mfs.mw.portal.api.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.ParserConfigurationException;
import mfs.mw.portal.api.dto.BulkRecord;
import mfs.mw.portal.api.dto.BulkFile;
import mfs.mw.portal.api.dto.ErrorDetail;
import mfs.mw.portal.api.exception.MwException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads the first sheet of a bulk disbursement workbook (XLSX, ticket 129, CH-POR-01) into the
 * same records as a CSV, so {@link BulkRowValidator} applies the same rules. Bounded, as the file
 * comes from outside: the ZIP is first inflated into nothing with a cap on entries and inflated
 * bytes (zip bomb guard), a workbook with an external link part is refused, the sheet is read
 * with a SAX parser (never the POI user model) and stops after the row and cell caps, cell text is
 * cut at {@link #MAX_CELL_CHARS}, the shared strings are read with their own SAX parser under a
 * count cap, numbers are expanded only when small ({@link #plainNumber}), and a formula anywhere is refused, never evaluated (its cached
 * result is not trusted either). Cell values: shared and inline strings as text, numbers in plain
 * decimal notation ({@code 1500.5}, {@code 10000000}), booleans {@code TRUE}/{@code FALSE}. Line
 * numbers are the sheet's row numbers (header = row 1).
 */
final class BulkXlsxReader {

  static final int MAX_ZIP_ENTRIES = 1_000;
  static final long MAX_INFLATED_BYTES = 64L * 1024 * 1024;
  static final int MAX_CELL_CHARS = 256;
  static final int MAX_SCALE = 20;
  static final int MAX_PRECISION = 30;
  private static final Pattern NUMBER = Pattern.compile("-?[0-9]{1,30}(?:\\.[0-9]{1,30})?(?:[eE][+-]?[0-9]{1,2})?");
  /** Columns kept per row; a value further right only makes the row "too wide". */
  private static final int KEPT_COLUMNS = 4;

  private final int maxRows;

  BulkXlsxReader(int maxRows) {
    this.maxRows = maxRows;
  }

  BulkFile parse(byte[] file) {
    preScan(file);
    SheetHandler sheet = new SheetHandler();
    try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(file))) {
      sheet.strings = sharedStrings(pkg);
      Iterator<InputStream> sheets = new XSSFReader(pkg).getSheetsData();
      if (!sheets.hasNext()) {
        throw notAWorkbook();
      }
      try (InputStream in = sheets.next()) {
        XMLReader reader = XMLHelper.newXMLReader();
        reader.setContentHandler(sheet);
        reader.parse(new InputSource(in));
      }
    } catch (MwException e) {
      throw e;
    } catch (Stop stop) {
      throw stop.error;
    } catch (IOException | OpenXML4JException | SAXException | ParserConfigurationException | RuntimeException e) {
      throw notAWorkbook();
    }
    return BulkRowValidator.validate(sheet.header, sheet.rows, maxRows);
  }

  /**
   * The workbook's shared strings, read with a SAX parser (never POI's table, which loads them
   * all): at most {@link #maxSharedStrings()} entries, else {@code MW-VAL-030}, each cut at {@link
   * #MAX_CELL_CHARS} while parsing; phonetic runs ({@code rPh}) are skipped. None when the part is
   * absent.
   */
  private List<String> sharedStrings(OPCPackage pkg)
      throws IOException, SAXException, ParserConfigurationException {
    List<PackagePart> parts = pkg.getPartsByContentType(XSSFRelation.SHARED_STRINGS.getContentType());
    if (parts.isEmpty()) {
      return List.of();
    }
    SharedStringsHandler handler = new SharedStringsHandler(maxSharedStrings());
    try (InputStream in = parts.get(0).getInputStream()) {
      XMLReader reader = XMLHelper.newXMLReader();
      reader.setContentHandler(handler);
      reader.parse(new InputSource(in));
    }
    return handler.strings;
  }

  /** Shared strings a valid file can need: a few per row (header and three columns). */
  private long maxSharedStrings() {
    return (long) (maxRows + 1) * 4;
  }

  private final class SharedStringsHandler extends DefaultHandler {

    final List<String> strings = new ArrayList<>();
    private final long max;
    private StringBuilder text;
    private boolean capture;
    private int phonetic;

    SharedStringsHandler(long max) {
      this.max = max;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) {
      switch (name(localName, qName)) {
        case "si" -> {
          if (strings.size() >= max) {
            throw new Stop(BulkRowValidator.tooLarge("File has more shared strings than " + max));
          }
          text = new StringBuilder();
        }
        case "rPh" -> phonetic++;
        case "t" -> capture = text != null && phonetic == 0;
        default -> { }
      }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      if (capture && text.length() < MAX_CELL_CHARS) {
        text.append(ch, start, Math.min(length, MAX_CELL_CHARS - text.length()));
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      switch (name(localName, qName)) {
        case "t" -> capture = false;
        case "rPh" -> phonetic--;
        case "si" -> {
          strings.add(text.toString());
          text = null;
        }
        default -> { }
      }
    }
  }

  /** Inflates every entry once, counting: too many entries or bytes, or an external link, refuse. */
  private static void preScan(byte[] file) {
    int entries = 0;
    long inflated = 0;
    byte[] buf = new byte[8192];
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
      for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
        if (++entries > MAX_ZIP_ENTRIES) {
          throw notAWorkbook();
        }
        String name = e.getName().toLowerCase(Locale.ROOT);
        if (name.startsWith("xl/externallinks/")) {
          throw BulkRowValidator.invalid(new ErrorDetail("file", "external links are not accepted"));
        }
        for (int n = zip.read(buf); n > 0; n = zip.read(buf)) {
          inflated += n;
          if (inflated > MAX_INFLATED_BYTES) {
            throw BulkRowValidator.tooLarge("File inflates to more than " + MAX_INFLATED_BYTES / (1024 * 1024) + " MiB");
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      if (e instanceof MwException mw) {
        throw mw;
      }
      throw notAWorkbook();
    }
    if (entries == 0) {
      throw notAWorkbook();
    }
  }

  private static MwException notAWorkbook() {
    return BulkRowValidator.invalid(new ErrorDetail("file", "is not a readable XLSX workbook"));
  }

  /** Ends the SAX parse early with the error to report. */
  private static final class Stop extends RuntimeException {
    final transient MwException error;

    Stop(MwException error) {
      super(null, null, false, false);
      this.error = error;
    }
  }

  private final class SheetHandler extends DefaultHandler {

    List<String> strings;
    List<String> header;
    final List<BulkRecord> rows = new ArrayList<>();

    private final long maxRowElements = (long) maxRows * 4 + 100;
    private final long maxCells = (long) (maxRows + 1) * 32;
    private long rowElements;
    private long cells;
    private int rowNum;
    private TreeMap<Integer, String> rowCells;
    private int widest;
    private int col;
    private String type;
    private StringBuilder text;
    private boolean capture;

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) {
      switch (name(localName, qName)) {
        case "row" -> {
          if (++rowElements > maxRowElements) {
            throw new Stop(oversize());
          }
          String r = atts.getValue("r");
          rowNum = r == null ? rowNum + 1 : Integer.parseInt(r);
          rowCells = new TreeMap<>();
          widest = -1;
          col = -1;
        }
        case "c" -> {
          if (++cells > maxCells) {
            throw new Stop(oversize());
          }
          String ref = atts.getValue("r");
          col = ref == null ? col + 1 : column(ref);
          type = atts.getValue("t");
          text = new StringBuilder();
        }
        case "f" -> throw new Stop(
            BulkRowValidator.invalid(
                new ErrorDetail(fieldName(rowNum, col), "formulas are not accepted; enter the value")));
        case "v", "t" -> capture = text != null;
        default -> { }
      }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      if (capture && text.length() < MAX_CELL_CHARS) {
        text.append(ch, start, Math.min(length, MAX_CELL_CHARS - text.length()));
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      switch (name(localName, qName)) {
        case "v", "t" -> capture = false;
        case "c" -> {
          String value = value(text.toString()).strip();
          if (!value.isEmpty()) {
            widest = Math.max(widest, col);
            if (col < KEPT_COLUMNS) {
              rowCells.put(col, value);
            }
          }
          text = null;
        }
        case "row" -> endRow();
        default -> { }
      }
    }

    private void endRow() {
      if (widest < 0) {
        return;
      }
      List<String> fields = new ArrayList<>();
      for (int c = 0; c <= Math.min(widest, KEPT_COLUMNS - 1); c++) {
        fields.add(rowCells.getOrDefault(c, ""));
      }
      if (rowNum == 1) {
        header = fields;
        return;
      }
      if (rows.size() >= maxRows) {
        throw new Stop(oversize());
      }
      rows.add(new BulkRecord(rowNum, fields));
    }

    private String value(String raw) {
      if (type == null || type.equals("n")) {
        return plainNumber(raw.strip());
      }
      return switch (type) {
        case "s" -> sharedString(raw);
        case "b" -> "1".equals(raw.strip()) ? "TRUE" : "FALSE";
        default -> raw;
      };
    }

    private String sharedString(String raw) {
      try {
        return strings.get(Integer.parseInt(raw.strip()));
      } catch (RuntimeException e) {
        throw new Stop(notAWorkbook());
      }
    }

    private MwException oversize() {
      return BulkRowValidator.tooLarge("File has more than " + maxRows + " rows (or too many cells)");
    }
  }

  /**
   * A numeric cell in plain decimal notation ({@code 9.87654321E8} -> {@code 987654321}), only
   * when it is small: the raw text must match {@link #NUMBER} (an exponent of at most 2 digits) and
   * the value have a scale within +-{@link #MAX_SCALE} and at most {@link #MAX_PRECISION} digits.
   * Anything else keeps its raw text, which then fails the amount or MSISDN rule; a value like
   * {@code 1E999999999} is never expanded (it would take the heap).
   */
  static String plainNumber(String raw) {
    if (!NUMBER.matcher(raw).matches()) {
      return raw;
    }
    BigDecimal value = new BigDecimal(raw);
    if (Math.abs(value.scale()) > MAX_SCALE || value.precision() > MAX_PRECISION) {
      return raw;
    }
    return value.stripTrailingZeros().toPlainString();
  }

  private static String name(String localName, String qName) {
    if (localName != null && !localName.isEmpty()) {
      return localName;
    }
    int colon = qName.indexOf(':');
    return colon < 0 ? qName : qName.substring(colon + 1);
  }

  /** Zero-based column of a cell reference like {@code AB12}. */
  private static int column(String ref) {
    int c = 0;
    for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++) {
      c = c * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
    }
    return c - 1;
  }

  private static String fieldName(int line, int col) {
    return col >= 0 && col < BulkRowValidator.HEADER.size()
        ? "line " + line + ": " + BulkRowValidator.HEADER.get(col)
        : "line " + line;
  }
}
