package mfs.mw.audit.dw.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import mfs.mw.audit.dw.client.MsisdnTokeniser;
import mfs.mw.audit.dw.config.AuditQueryProperties;
import mfs.mw.audit.dw.dto.TxnStatusEvent;
import mfs.mw.audit.dw.repository.TxnIndexRepository;
import mfs.mw.audit.dw.util.Msisdns;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Keeps the search projection {@code audit.txn_index} (ticket 130, FRS AUD-03) from {@code
 * mw.txn.status.v1}: channel, type, latest status, amount, partner and the {@code TOKENISE_MSISDN}
 * tokens of the parties' MSISDNs (initiator, debit and credit party whose id is an MSISDN). The
 * MSISDNs are tokenised once per transaction (the first event that has them) and are never stored
 * or logged. A failed tokenisation throws, so the record goes to the retry topics.
 */
@Service
public class TxnIndexService {

  private final TxnIndexRepository repository;
  private final ObjectProvider<MsisdnTokeniser> tokeniser;
  private final AuditQueryProperties props;

  public TxnIndexService(
      TxnIndexRepository repository, ObjectProvider<MsisdnTokeniser> tokeniser, AuditQueryProperties props) {
    this.repository = repository;
    this.tokeniser = tokeniser;
    this.props = props;
  }

  /** @throws IllegalArgumentException when a field the projection needs is missing */
  public void apply(TxnStatusEvent e) {
    if (e.txnId() == null || e.status() == null || e.status().isBlank() || e.updatedAt() == null) {
      throw new IllegalArgumentException("txn status event lacks txnId, status or updatedAt");
    }
    List<String> tokens = repository.tokens(e.txnId()).orElseGet(() -> tokenise(e));
    repository.upsert(
        new TxnIndexRepository.Row(
            e.txnId(),
            e.channel(),
            e.txnType(),
            e.status(),
            e.amount() == null ? null : e.amount().valueMinor(),
            e.amount() == null ? null : e.amount().currency(),
            e.partner() == null ? null : e.partner().partnerId(),
            tokens,
            e.requestedAt(),
            e.updatedAt()));
  }

  /** The token of one MSISDN for a search, or empty when tokenising is off. */
  public Optional<String> tokenFor(String normalisedMsisdn) {
    MsisdnTokeniser t = props.tokeniseMsisdn() ? tokeniser.getIfAvailable() : null;
    return t == null ? Optional.empty() : Optional.of(t.tokenise(List.of(normalisedMsisdn)).get(0));
  }

  private List<String> tokenise(TxnStatusEvent e) {
    MsisdnTokeniser t = props.tokeniseMsisdn() ? tokeniser.getIfAvailable() : null;
    if (t == null) {
      return List.of();
    }
    Set<String> msisdns = new LinkedHashSet<>();
    Stream.of(e.initiator(), e.debitParty(), e.creditParty())
        .filter(p -> p != null && p.id() != null)
        .forEach(p -> Msisdns.normalise(p.id()).ifPresent(msisdns::add));
    return msisdns.isEmpty() ? List.of() : List.copyOf(new LinkedHashSet<>(t.tokenise(List.copyOf(msisdns))));
  }
}
