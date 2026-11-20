package mfs.mw.adapter.interop.mapping;

import com.prowidesoftware.swift.model.mx.AbstractMX;
import com.prowidesoftware.swift.model.mx.MxCamt02900109;
import com.prowidesoftware.swift.model.mx.MxCamt05600108;
import com.prowidesoftware.swift.model.mx.MxPacs00200110;
import com.prowidesoftware.swift.model.mx.MxPacs00400109;
import com.prowidesoftware.swift.model.mx.MxPacs00800108;
import com.prowidesoftware.swift.model.mx.MxPacs02800103;
import com.prowidesoftware.swift.model.mx.MxWriteParams;
import com.prowidesoftware.swift.model.mx.dic.AccountIdentification4Choice;
import com.prowidesoftware.swift.model.mx.dic.ActiveCurrencyAndAmount;
import com.prowidesoftware.swift.model.mx.dic.ActiveOrHistoricCurrencyAndAmount;
import com.prowidesoftware.swift.model.mx.dic.BranchAndFinancialInstitutionIdentification6;
import com.prowidesoftware.swift.model.mx.dic.CancellationReason33Choice;
import com.prowidesoftware.swift.model.mx.dic.Case5;
import com.prowidesoftware.swift.model.mx.dic.CaseAssignment5;
import com.prowidesoftware.swift.model.mx.dic.CashAccount38;
import com.prowidesoftware.swift.model.mx.dic.ChargeBearerType1Code;
import com.prowidesoftware.swift.model.mx.dic.CreditTransferTransaction39;
import com.prowidesoftware.swift.model.mx.dic.FIToFICustomerCreditTransferV08;
import com.prowidesoftware.swift.model.mx.dic.FIToFIPaymentCancellationRequestV08;
import com.prowidesoftware.swift.model.mx.dic.FIToFIPaymentStatusReportV10;
import com.prowidesoftware.swift.model.mx.dic.FIToFIPaymentStatusRequestV03;
import com.prowidesoftware.swift.model.mx.dic.FinancialInstitutionIdentification18;
import com.prowidesoftware.swift.model.mx.dic.GenericAccountIdentification1;
import com.prowidesoftware.swift.model.mx.dic.GroupHeader90;
import com.prowidesoftware.swift.model.mx.dic.GroupHeader91;
import com.prowidesoftware.swift.model.mx.dic.GroupHeader93;
import com.prowidesoftware.swift.model.mx.dic.InvestigationStatus5Choice;
import com.prowidesoftware.swift.model.mx.dic.OriginalGroupInformation29;
import com.prowidesoftware.swift.model.mx.dic.Party40Choice;
import com.prowidesoftware.swift.model.mx.dic.PartyIdentification135;
import com.prowidesoftware.swift.model.mx.dic.PaymentCancellationReason5;
import com.prowidesoftware.swift.model.mx.dic.PaymentIdentification7;
import com.prowidesoftware.swift.model.mx.dic.PaymentReturnReason6;
import com.prowidesoftware.swift.model.mx.dic.PaymentReturnV09;
import com.prowidesoftware.swift.model.mx.dic.PaymentTransaction102;
import com.prowidesoftware.swift.model.mx.dic.PaymentTransaction106;
import com.prowidesoftware.swift.model.mx.dic.PaymentTransaction110;
import com.prowidesoftware.swift.model.mx.dic.PaymentTransaction112;
import com.prowidesoftware.swift.model.mx.dic.PaymentTransaction113;
import com.prowidesoftware.swift.model.mx.dic.ResolutionOfInvestigationV09;
import com.prowidesoftware.swift.model.mx.dic.ReturnReason5Choice;
import com.prowidesoftware.swift.model.mx.dic.SettlementInstruction7;
import com.prowidesoftware.swift.model.mx.dic.SettlementMethod1Code;
import com.prowidesoftware.swift.model.mx.dic.StatusReason6Choice;
import com.prowidesoftware.swift.model.mx.dic.StatusReasonInformation12;
import com.prowidesoftware.swift.model.mx.dic.UnderlyingTransaction22;
import com.prowidesoftware.swift.model.mx.dic.UnderlyingTransaction23;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import mfs.mw.adapter.interop.dto.Money;
import mfs.mw.adapter.interop.dto.iso.NpsbParty;
import mfs.mw.adapter.interop.dto.iso.NpsbResolution;
import mfs.mw.adapter.interop.dto.iso.NpsbReturn;
import mfs.mw.adapter.interop.dto.iso.NpsbReturnRequest;
import mfs.mw.adapter.interop.dto.iso.NpsbStatus;
import mfs.mw.adapter.interop.dto.iso.NpsbStatusRequest;
import mfs.mw.adapter.interop.dto.iso.NpsbTransfer;
import mfs.mw.adapter.interop.exception.MwException;
import org.springframework.stereotype.Component;

/**
 * Canonical NPSB records to ISO 20022 XML and back (ticket 121b), built and read with Prowide
 * ({@code pw-iso20022}), never with string templates. Every message written is validated against its
 * XSD before it leaves; every message read is validated before Prowide parses it
 * ({@link Iso20022Schemas}). One transaction per message. Amounts: integer poisha <-> ISO decimal
 * with two places. The {@code Document} only: the NPSB business header and the XML-DSig signature
 * (mw-crypto, {@code client/CryptoXmlSigner}) are added around it by the caller.
 */
@Component
public class Iso20022Mapper {

  private final Iso20022Schemas schemas;

  public Iso20022Mapper(Iso20022Schemas schemas) {
    this.schemas = schemas;
  }

  // ---- pacs.008 -------------------------------------------------------------------------------

  public String pacs008(NpsbTransfer t) {
    GroupHeader93 hdr = new GroupHeader93()
        .setMsgId(t.msgId())
        .setCreDtTm(t.createdAt())
        .setNbOfTxs("1")
        .setSttlmInf(new SettlementInstruction7().setSttlmMtd(SettlementMethod1Code.CLRG));
    CreditTransferTransaction39 tx = new CreditTransferTransaction39()
        .setPmtId(new PaymentIdentification7().setEndToEndId(t.endToEndId()).setTxId(t.txId()))
        .setIntrBkSttlmAmt(active(t.amount()))
        .setIntrBkSttlmDt(t.settlementDate())
        .setChrgBr(ChargeBearerType1Code.SLEV)
        .setDbtr(new PartyIdentification135().setNm(t.debtor().name()))
        .setDbtrAcct(account(t.debtor().account()))
        .setDbtrAgt(agent(t.debtor().agent()))
        .setCdtrAgt(agent(t.creditor().agent()))
        .setCdtr(new PartyIdentification135().setNm(t.creditor().name()))
        .setCdtrAcct(account(t.creditor().account()));
    FIToFICustomerCreditTransferV08 body = new FIToFICustomerCreditTransferV08().setGrpHdr(hdr);
    body.getCdtTrfTxInf().add(tx);
    return write(IsoMessage.PACS_008, new MxPacs00800108().setFIToFICstmrCdtTrf(body));
  }

  public NpsbTransfer readPacs008(String xml) {
    FIToFICustomerCreditTransferV08 body =
        read(IsoMessage.PACS_008, xml, MxPacs00800108::parse).getFIToFICstmrCdtTrf();
    CreditTransferTransaction39 tx = only(body.getCdtTrfTxInf(), IsoMessage.PACS_008);
    return new NpsbTransfer(
        body.getGrpHdr().getMsgId(),
        body.getGrpHdr().getCreDtTm(),
        tx.getPmtId().getEndToEndId(),
        tx.getPmtId().getTxId(),
        money(tx.getIntrBkSttlmAmt().getValue(), tx.getIntrBkSttlmAmt().getCcy()),
        tx.getIntrBkSttlmDt(),
        new NpsbParty(tx.getDbtr().getNm(), accountId(tx.getDbtrAcct()), agentId(tx.getDbtrAgt())),
        new NpsbParty(tx.getCdtr().getNm(), accountId(tx.getCdtrAcct()), agentId(tx.getCdtrAgt())));
  }

  // ---- pacs.002 -------------------------------------------------------------------------------

  public String pacs002(NpsbStatus s) {
    PaymentTransaction110 tx = new PaymentTransaction110()
        .setOrgnlGrpInf(original(s.originalMsgId(), IsoMessage.PACS_008))
        .setOrgnlEndToEndId(s.originalEndToEndId())
        .setOrgnlTxId(s.originalTxId())
        .setTxSts(s.status())
        .setClrSysRef(s.clearingRef());
    if (s.reasonCode() != null) {
      tx.getStsRsnInf().add(new StatusReasonInformation12().setRsn(new StatusReason6Choice().setCd(s.reasonCode())));
    }
    FIToFIPaymentStatusReportV10 body = new FIToFIPaymentStatusReportV10()
        .setGrpHdr(new GroupHeader91().setMsgId(s.msgId()).setCreDtTm(s.createdAt()));
    body.getTxInfAndSts().add(tx);
    return write(IsoMessage.PACS_002, new MxPacs00200110().setFIToFIPmtStsRpt(body));
  }

  public NpsbStatus readPacs002(String xml) {
    FIToFIPaymentStatusReportV10 body = read(IsoMessage.PACS_002, xml, MxPacs00200110::parse).getFIToFIPmtStsRpt();
    PaymentTransaction110 tx = only(body.getTxInfAndSts(), IsoMessage.PACS_002);
    String reason = tx.getStsRsnInf().isEmpty() || tx.getStsRsnInf().getFirst().getRsn() == null
        ? null
        : tx.getStsRsnInf().getFirst().getRsn().getCd();
    return new NpsbStatus(
        body.getGrpHdr().getMsgId(),
        body.getGrpHdr().getCreDtTm(),
        tx.getOrgnlGrpInf() == null ? null : tx.getOrgnlGrpInf().getOrgnlMsgId(),
        tx.getOrgnlEndToEndId(),
        tx.getOrgnlTxId(),
        tx.getTxSts(),
        reason,
        tx.getClrSysRef());
  }

  // ---- pacs.028 -------------------------------------------------------------------------------

  public String pacs028(NpsbStatusRequest r) {
    PaymentTransaction113 tx = new PaymentTransaction113()
        .setOrgnlGrpInf(original(r.originalMsgId(), IsoMessage.PACS_008))
        .setOrgnlEndToEndId(r.originalEndToEndId())
        .setOrgnlTxId(r.originalTxId());
    FIToFIPaymentStatusRequestV03 body = new FIToFIPaymentStatusRequestV03()
        .setGrpHdr(new GroupHeader91().setMsgId(r.msgId()).setCreDtTm(r.createdAt()).setInstgAgt(agent(r.instructingAgent())));
    body.getTxInf().add(tx);
    return write(IsoMessage.PACS_028, new MxPacs02800103().setFIToFIPmtStsReq(body));
  }

  public NpsbStatusRequest readPacs028(String xml) {
    FIToFIPaymentStatusRequestV03 body = read(IsoMessage.PACS_028, xml, MxPacs02800103::parse).getFIToFIPmtStsReq();
    PaymentTransaction113 tx = only(body.getTxInf(), IsoMessage.PACS_028);
    return new NpsbStatusRequest(
        body.getGrpHdr().getMsgId(),
        body.getGrpHdr().getCreDtTm(),
        tx.getOrgnlGrpInf() == null ? null : tx.getOrgnlGrpInf().getOrgnlMsgId(),
        tx.getOrgnlEndToEndId(),
        tx.getOrgnlTxId(),
        agentId(body.getGrpHdr().getInstgAgt()));
  }

  // ---- camt.056 -------------------------------------------------------------------------------

  public String camt056(NpsbReturnRequest r) {
    PaymentTransaction106 tx = new PaymentTransaction106()
        .setOrgnlGrpInf(original(r.originalMsgId(), IsoMessage.PACS_008))
        .setOrgnlEndToEndId(r.originalEndToEndId())
        .setOrgnlTxId(r.originalTxId())
        .setOrgnlIntrBkSttlmAmt(historic(r.originalAmount()));
    tx.getCxlRsnInf().add(new PaymentCancellationReason5().setRsn(new CancellationReason33Choice().setCd(r.reasonCode())));
    UnderlyingTransaction23 underlying = new UnderlyingTransaction23();
    underlying.getTxInf().add(tx);
    FIToFIPaymentCancellationRequestV08 body = new FIToFIPaymentCancellationRequestV08()
        .setAssgnmt(assignment(r.assignmentId(), r.createdAt(), r.assigner(), r.assignee()));
    body.getUndrlyg().add(underlying);
    return write(IsoMessage.CAMT_056, new MxCamt05600108().setFIToFIPmtCxlReq(body));
  }

  public NpsbReturnRequest readCamt056(String xml) {
    FIToFIPaymentCancellationRequestV08 body = read(IsoMessage.CAMT_056, xml, MxCamt05600108::parse).getFIToFIPmtCxlReq();
    PaymentTransaction106 tx = only(only(body.getUndrlyg(), IsoMessage.CAMT_056).getTxInf(), IsoMessage.CAMT_056);
    CaseAssignment5 a = body.getAssgnmt();
    return new NpsbReturnRequest(
        a.getId(),
        a.getCreDtTm(),
        tx.getOrgnlGrpInf() == null ? null : tx.getOrgnlGrpInf().getOrgnlMsgId(),
        tx.getOrgnlEndToEndId(),
        tx.getOrgnlTxId(),
        money(tx.getOrgnlIntrBkSttlmAmt().getValue(), tx.getOrgnlIntrBkSttlmAmt().getCcy()),
        tx.getCxlRsnInf().isEmpty() ? null : tx.getCxlRsnInf().getFirst().getRsn().getCd(),
        agentId(a.getAssgnr().getAgt()),
        agentId(a.getAssgne().getAgt()));
  }

  // ---- camt.029 -------------------------------------------------------------------------------

  public String camt029(NpsbResolution r) {
    PaymentTransaction102 tx = new PaymentTransaction102()
        .setOrgnlEndToEndId(r.originalEndToEndId())
        .setOrgnlTxId(r.originalTxId());
    UnderlyingTransaction22 details = new UnderlyingTransaction22();
    details.getTxInfAndSts().add(tx);
    ResolutionOfInvestigationV09 body = new ResolutionOfInvestigationV09()
        .setAssgnmt(assignment(r.assignmentId(), r.createdAt(), r.assigner(), r.assignee()))
        .setRslvdCase(new Case5().setId(r.resolvedCaseId()).setCretr(new Party40Choice().setAgt(agent(r.assignee()))))
        .setSts(new InvestigationStatus5Choice().setConf(r.confirmation()));
    body.getCxlDtls().add(details);
    return write(IsoMessage.CAMT_029, new MxCamt02900109().setRsltnOfInvstgtn(body));
  }

  public NpsbResolution readCamt029(String xml) {
    ResolutionOfInvestigationV09 body = read(IsoMessage.CAMT_029, xml, MxCamt02900109::parse).getRsltnOfInvstgtn();
    PaymentTransaction102 tx = only(only(body.getCxlDtls(), IsoMessage.CAMT_029).getTxInfAndSts(), IsoMessage.CAMT_029);
    CaseAssignment5 a = body.getAssgnmt();
    return new NpsbResolution(
        a.getId(),
        a.getCreDtTm(),
        body.getRslvdCase() == null ? null : body.getRslvdCase().getId(),
        body.getSts().getConf(),
        tx.getOrgnlEndToEndId(),
        tx.getOrgnlTxId(),
        agentId(a.getAssgnr().getAgt()),
        agentId(a.getAssgne().getAgt()));
  }

  // ---- pacs.004 -------------------------------------------------------------------------------

  public String pacs004(NpsbReturn r) {
    PaymentTransaction112 tx = new PaymentTransaction112()
        .setRtrId(r.returnId())
        .setOrgnlGrpInf(original(r.originalMsgId(), IsoMessage.PACS_008))
        .setOrgnlEndToEndId(r.originalEndToEndId())
        .setOrgnlTxId(r.originalTxId())
        .setRtrdIntrBkSttlmAmt(active(r.returnedAmount()))
        .setIntrBkSttlmDt(r.settlementDate());
    tx.getRtrRsnInf().add(new PaymentReturnReason6().setRsn(new ReturnReason5Choice().setCd(r.reasonCode())));
    PaymentReturnV09 body = new PaymentReturnV09().setGrpHdr(new GroupHeader90()
        .setMsgId(r.msgId())
        .setCreDtTm(r.createdAt())
        .setNbOfTxs("1")
        .setSttlmInf(new SettlementInstruction7().setSttlmMtd(SettlementMethod1Code.CLRG)));
    body.getTxInf().add(tx);
    return write(IsoMessage.PACS_004, new MxPacs00400109().setPmtRtr(body));
  }

  public NpsbReturn readPacs004(String xml) {
    PaymentReturnV09 body = read(IsoMessage.PACS_004, xml, MxPacs00400109::parse).getPmtRtr();
    PaymentTransaction112 tx = only(body.getTxInf(), IsoMessage.PACS_004);
    return new NpsbReturn(
        body.getGrpHdr().getMsgId(),
        body.getGrpHdr().getCreDtTm(),
        tx.getRtrId(),
        tx.getOrgnlGrpInf() == null ? null : tx.getOrgnlGrpInf().getOrgnlMsgId(),
        tx.getOrgnlEndToEndId(),
        tx.getOrgnlTxId(),
        money(tx.getRtrdIntrBkSttlmAmt().getValue(), tx.getRtrdIntrBkSttlmAmt().getCcy()),
        tx.getIntrBkSttlmDt(),
        tx.getRtrRsnInf().isEmpty() ? null : tx.getRtrRsnInf().getFirst().getRsn().getCd());
  }

  // ---- helpers --------------------------------------------------------------------------------

  private String write(IsoMessage message, AbstractMX mx) {
    MxWriteParams params = new MxWriteParams();
    params.prefix = null; // default namespace, no "Doc:" prefix
    params.includeXMLDeclaration = true;
    String xml = mx.document(params);
    schemas.validate(message, xml);
    return xml;
  }

  private <T extends AbstractMX> T read(IsoMessage message, String xml, Function<String, T> parser) {
    schemas.validate(message, xml);
    T mx = parser.apply(xml);
    if (mx == null) {
      throw new MwException(Iso20022Schemas.INVALID, message.messageName() + " does not parse");
    }
    return mx;
  }

  private static <T> T only(List<T> items, IsoMessage message) {
    if (items.size() != 1) {
      throw new MwException(Iso20022Schemas.INVALID, message.messageName() + " must carry exactly one transaction");
    }
    return items.getFirst();
  }

  private static OriginalGroupInformation29 original(String msgId, IsoMessage of) {
    return msgId == null ? null : new OriginalGroupInformation29().setOrgnlMsgId(msgId).setOrgnlMsgNmId(of.messageName());
  }

  private static CaseAssignment5 assignment(String id, java.time.OffsetDateTime at, String assigner, String assignee) {
    return new CaseAssignment5()
        .setId(id)
        .setCreDtTm(at)
        .setAssgnr(new Party40Choice().setAgt(agent(assigner)))
        .setAssgne(new Party40Choice().setAgt(agent(assignee)));
  }

  private static BranchAndFinancialInstitutionIdentification6 agent(String bic) {
    return new BranchAndFinancialInstitutionIdentification6()
        .setFinInstnId(new FinancialInstitutionIdentification18().setBICFI(bic));
  }

  private static String agentId(BranchAndFinancialInstitutionIdentification6 agent) {
    return agent == null || agent.getFinInstnId() == null ? null : agent.getFinInstnId().getBICFI();
  }

  private static CashAccount38 account(String id) {
    return new CashAccount38().setId(new AccountIdentification4Choice().setOthr(new GenericAccountIdentification1().setId(id)));
  }

  private static String accountId(CashAccount38 account) {
    return account == null || account.getId() == null || account.getId().getOthr() == null
        ? null
        : account.getId().getOthr().getId();
  }

  private static ActiveCurrencyAndAmount active(Money m) {
    return new ActiveCurrencyAndAmount().setValue(BigDecimal.valueOf(m.valueMinor(), 2)).setCcy(m.currency());
  }

  private static ActiveOrHistoricCurrencyAndAmount historic(Money m) {
    return new ActiveOrHistoricCurrencyAndAmount().setValue(BigDecimal.valueOf(m.valueMinor(), 2)).setCcy(m.currency());
  }

  private static Money money(BigDecimal value, String currency) {
    try {
      return new Money(value.movePointRight(2).longValueExact(), currency);
    } catch (ArithmeticException e) {
      throw new MwException(Iso20022Schemas.INVALID, "amount has more than two decimals");
    }
  }
}
