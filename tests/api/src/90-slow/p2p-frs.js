// Ticket 104, T5. The dev inquiry schedule (6s, 2s, 5s, 10s, 30s, 1m) plus the 30s answer wait is
// about 150s, so this stays in folder 90. Needs the short timers of deploy/dev.env.defaults.
// The core applies the transfer and then never lets an answer or a status inquiry through, so the
// saga ends in MANUAL_REVIEW with the money already posted and operations are alerted (ticket 96).
export const helpers = [
  async function prepare(MW) {
    await MW.mockReset('core');
  },
];

export default [
  {
    name: '[p2p frs] T5 a core that never answers ends in MANUAL_REVIEW and alerts operations',
    run: async (MW, S) => {
      await prepare(MW);
      S.after(() => MW.mockReset('core'));
      await S.note(['01711000001', '01711000002', 'FEE_INCOME', 'VAT_PAYABLE', 'COMMISSION_PAYABLE']);
      await MW.mockScenario('core', 'transfer', 'TIMEOUT', { delayMs: 4000 });
      await MW.mockScenario('core', 'query', 'TIMEOUT', { delayMs: 4000 });
      await S.previewP2p('customer01', '01711000002', 1000, MW.pin);
      S.status(202);
      S.txnStatus('PROCESSING');
      await S.adminShows('MANUAL_REVIEW', 200);
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435, 'VAT_PAYABLE': 65, 'COMMISSION_PAYABLE': 0 });
      await S.sms('01700000999', 'Bits ops: P2P of Tk 10.00 needs manual review. Reason MW-TMO-004. TrxID {txnId}', 60);
      await S.mail('ops@bits.test', 'Bits ops: manual review needed, TrxID {txnId}');
    },
  },
];
