package mfs.mw.simulators.dto;

/**
 * One biller the aggregator serves, with the protocol it is reached over: the REST API ({@code
 * /billagg/v1/bills/fetch}, {@code /billagg/v1/payments}) or the SOAP front end ({@code /billagg/v1/soap}).
 */
public record Biller(String code, String name, String category, Protocol protocol) {

  /** The aggregator front end a biller is served on. */
  public enum Protocol { REST, SOAP }

  /** A REST biller (the default of the aggregator). */
  public Biller(String code, String name, String category) {
    this(code, name, category, Protocol.REST);
  }
}
