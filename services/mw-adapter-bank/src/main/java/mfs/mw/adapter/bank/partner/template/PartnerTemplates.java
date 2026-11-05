package mfs.mw.adapter.bank.partner.template;

import java.util.Set;

/** Names of the route templates this adapter ships (spec 12.1 layer 1). */
public final class PartnerTemplates {

  /** Synchronous REST/JSON: one request, the answer in the reply. */
  public static final String REQUEST_REPLY = "rest-json-request-reply";

  /** REST/JSON acknowledged first (PENDING), the outcome follows by signed callback. */
  public static final String ASYNC_CALLBACK = "rest-json-async-callback";

  public static final Set<String> ALL = Set.of(REQUEST_REPLY, ASYNC_CALLBACK);

  /** The operation whose answers may be NOT_FOUND (spec 7.4). */
  public static final String INQUIRY_OPERATION = "STATUS_QUERY";

  /** The operation whose mapping reads a callback. */
  public static final String CALLBACK_OPERATION = "CALLBACK";

  private PartnerTemplates() {}

  /** Camel endpoint of a partner instance's route. */
  public static String endpoint(String instance) {
    return "direct:partner-" + instance;
  }

  /** Camel route id of a partner instance's route. */
  public static String routeId(String instance) {
    return "partner-" + instance;
  }
}
