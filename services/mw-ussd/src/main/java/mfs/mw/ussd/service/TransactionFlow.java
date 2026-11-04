package mfs.mw.ussd.service;

import mfs.mw.ussd.dto.FlowOutcome;
import mfs.mw.ussd.dto.FlowRequest;

/** Runs an ACTION node's flow within the synchronous budget (spec 9.2, 7.5). */
public interface TransactionFlow {

  FlowOutcome submit(FlowRequest request);
}
