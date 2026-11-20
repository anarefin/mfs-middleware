package mfs.mw.adapter.interop.mapping;

/**
 * The ISO 20022 messages of the NPSB wire (ticket 121b), each with its message name and its XSD
 * under {@code src/main/resources/iso20022/} (the official ISO 20022 schemas, unchanged).
 */
public enum IsoMessage {
  /** FI-to-FI customer credit transfer: NPSB_OUT out, NPSB_IN in. */
  PACS_008("pacs.008.001.08"),
  /** Payment status report: the switch's answer to a pacs.008 / pacs.028, ours to an inbound pacs.008. */
  PACS_002("pacs.002.001.10"),
  /** Payment status request (the STATUS_QUERY of spec 7.4). */
  PACS_028("pacs.028.001.03"),
  /** Payment return. */
  PACS_004("pacs.004.001.09"),
  /** FI-to-FI payment cancellation (return) request: REVERSE. */
  CAMT_056("camt.056.001.08"),
  /** Resolution of investigation: the answer to a camt.056. */
  CAMT_029("camt.029.001.09");

  private final String messageName;

  IsoMessage(String messageName) {
    this.messageName = messageName;
  }

  /** The ISO message name, e.g. {@code pacs.008.001.08}. */
  public String messageName() {
    return messageName;
  }

  /** The target namespace of the message's {@code Document}. */
  public String namespace() {
    return "urn:iso:std:iso:20022:tech:xsd:" + messageName;
  }

  String xsd() {
    return "iso20022/" + messageName + ".xsd";
  }
}
