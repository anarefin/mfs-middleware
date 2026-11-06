// Port of reversal.feature. "Operations reverses the transaction" is mw-orchestrator's test-only
// POST /test/orchestrator/v1/reversals (ticket 58), which publishes the same REVERSAL request on
// mw.txn.request.v1 that the Cucumber suite sent with kafka-console-producer.
export default [
  {
    name: '[reversal] a completed P2P is reversed and every balance is restored',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.p2p('customer01', '01711000002', 1000, '1234');
      S.status(200);
      S.txnStatus('COMPLETED');
      await MW.testApi.reverse(S.txnId());
      await S.appShows('REVERSED');
      await S.noChange();
      await S.auditStates('RECEIVED, VALIDATING, POSTING, COMPLETED, REVERSING, REVERSED');
      await S.chainVerifies();
    },
  },
  {
    name: '[reversal] a second reversal of the same transaction moves no money again',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.p2p('customer01', '01711000002', 1000, '1234');
      S.status(200);
      await MW.testApi.reverse(S.txnId());
      await S.appShows('REVERSED');
      const second = (await MW.testApi.reverse(S.txnId())).body;
      const handled = await MW.poll(() => MW.testApi.handled(second.idempotencyKey), r => r.status === 200, 30000, 500);
      S.eq('the orchestrator has handled the second reversal request', [handled.status, handled.body && handled.body.handled], [200, true], String(handled));
      await S.appShows('REVERSED');
      await S.noChange();
      await S.auditStates('RECEIVED, VALIDATING, POSTING, COMPLETED, REVERSING, REVERSED');
    },
  },
  {
    name: '[reversal] a reversal the core refuses goes to manual review with an audit entry',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.reject('ReverseTransaction', 'INSUFFICIENT_BALANCE');
      await S.p2p('customer01', '01711000002', 1000, '1234');
      S.status(200);
      await MW.testApi.reverse(S.txnId());
      await S.appShows('MANUAL_REVIEW');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE-POOL': 500 });
      const rows = await S.auditStates('RECEIVED, VALIDATING, POSTING, COMPLETED, REVERSING, MANUAL_REVIEW');
      const mr = rows.find(e => e.after && e.after.status === 'MANUAL_REVIEW');
      S.eq('MANUAL_REVIEW carries MW-CORE-INSUFFICIENT_BALANCE', mr.after.errorCode, 'MW-CORE-INSUFFICIENT_BALANCE', mr);
    },
  },
];
