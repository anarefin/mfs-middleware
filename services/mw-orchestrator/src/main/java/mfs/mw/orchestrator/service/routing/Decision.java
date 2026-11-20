package mfs.mw.orchestrator.service.routing;

import java.util.List;

/** What routing decides for one request, before any money moves. */
public sealed interface Decision {

  /** Usable attempts, most preferred first. Never empty. */
  record Ready(List<Attempt> attempts) implements Decision {
    public Ready {
      attempts = List.copyOf(attempts);
    }
  }

  /** The caller named a partner no matching rule allows. */
  record UnknownPartner() implements Decision {}

  /**
   * Nothing usable. {@code code} is {@code MW-SYS-PARTNER_DISABLED} when a maintenance message
   * applies ({@code messageBn} set), otherwise {@code MW-SYS-PARTNER_CIRCUIT_OPEN}.
   */
  record Blocked(String code, String message, String messageBn) implements Decision {}
}
