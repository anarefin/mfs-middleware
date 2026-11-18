package mfs.mw.adapter.telco.partner.kamelet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mfs.mw.adapter.telco.partner.route.PartnerRecon;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

/**
 * The {@code mw-recon} step (ticket 86): a file read line by line into records for the sink, all
 * of one file in one call, and a file with a bad line failing as a whole, its content never in the
 * error.
 */
class MwReconStepTest {

  final List<PartnerRecon.File> files = new ArrayList<>();
  final List<PartnerRecon.Line> lines = new ArrayList<>();
  final PartnerRecon.Sink sink = (file, stream) -> {
    files.add(file);
    stream.forEach(lines::add);
  };
  final MwReconStep step = new MwReconStep(sink, MwReconStep.DEFAULT_MAX_LINES);

  static Exchange file(String name, String content) {
    Exchange exchange = new DefaultExchange(new DefaultCamelContext());
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    exchange.getMessage().setBody(new ByteArrayInputStream(bytes));
    exchange.getMessage().setHeader(Exchange.FILE_NAME_ONLY, name);
    exchange.getMessage().setHeader(Exchange.FILE_LENGTH, (long) bytes.length);
    exchange.getMessage().setHeader(Exchange.FILE_LAST_MODIFIED, 1795507200000L);
    return exchange;
  }

  @Test
  void theHeaderLineNamesTheColumnsAndEveryDataLineIsARecord() {
    step.publish(file("recon.csv", "reference,amount,status\nBITS01,500.00,SUCCESS\n\n\"BITS02\",\"1,20.50\",FAILED\n"),
        "acme", "", "comma");

    assertThat(files).containsExactly(new PartnerRecon.File("acme", "recon.csv", "recon.csv-73-1795507200000"));
    assertThat(lines).containsExactly(
        new PartnerRecon.Line(2, false, Map.of("reference", "BITS01", "amount", "500.00", "status", "SUCCESS")),
        new PartnerRecon.Line(4, true, Map.of("reference", "BITS02", "amount", "1,20.50", "status", "FAILED")));
  }

  @Test
  void theRoutesColumnsReadAFileWithoutHeaderAndOtherDelimiters() {
    step.publish(file("recon.txt", "BITS01|500.00\r\nBITS02|7.00"), "acme", "reference,amount", "pipe");

    assertThat(lines).extracting(PartnerRecon.Line::lineNumber).containsExactly(1, 2);
    assertThat(lines.get(1).fields()).containsEntry("reference", "BITS02").containsEntry("amount", "7.00");
    assertThat(lines.get(1).last()).isTrue();
  }

  @Test
  void aLineWithTheWrongNumberOfFieldsFailsTheFileWithoutItsContent() {
    assertThatThrownBy(() -> step.publish(file("recon.csv", "reference,amount\nBITS01,500.00\n01712345678\n"), "acme", "", "comma"))
        .isInstanceOf(MwReconStep.BadReconFileException.class)
        .hasMessageContaining("line 3")
        .hasMessageNotContaining("01712345678");
  }

  @Test
  void aHeaderWithAColumnThatIsNoNameFailsTheFile() {
    assertThatThrownBy(() -> step.publish(file("recon.csv", "ref erence,amount\nBITS01,500.00\n"), "acme", "", "comma"))
        .isInstanceOf(MwReconStep.BadReconFileException.class)
        .hasMessageContaining("line 1");
  }

  @Test
  void aFileWithMoreDataLinesThanTheCapFailsAsAWhole() {
    MwReconStep capped = new MwReconStep(sink, 2);

    assertThatThrownBy(() -> capped.publish(file("recon.csv", "reference\nBITS01\nBITS02\n\nBITS03\n"), "acme", "", "comma"))
        .isInstanceOf(MwReconStep.BadReconFileException.class)
        .hasMessageContaining("more than 2 data lines")
        .hasMessageNotContaining("BITS03");
  }

  @Test
  void theCapIsAtLeastOneLine() {
    assertThatThrownBy(() -> new MwReconStep(sink, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anEmptyFileFails() {
    assertThatThrownBy(() -> step.publish(file("recon.csv", "reference,amount\n"), "acme", "", "comma"))
        .isInstanceOf(MwReconStep.BadReconFileException.class)
        .hasMessageContaining("no data line");
  }
}
