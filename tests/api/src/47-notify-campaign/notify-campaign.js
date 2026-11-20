// Ticket 111 (FRS NOT-07, NOT-09). Opt-out is promotional only, so the P2P SMS still arrives.
// The campaign is asserted as an approved change request: the send is throttled and held in quiet
// hours, and this folder does not wait for that SMS or opt the shared inbox out of transactions.
export default [
  {
    name: '[notify] an opted-out customer still gets the P2P SMS',
    run: async (MW, S) => {
      S.status(204, await MW.app.optOut('customer01'));
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.sms('01711000001', 'আপনি 0171****002 নম্বরে ৳10.00 পাঠিয়েছেন। ফি ৳5.00। TrxID {txnId}');
    },
  },
  {
    name: '[admin notify] an approved campaign change request is APPROVED',
    run: async (MW, S) => {
      const when = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().replace(/\.\d{3}Z$/, 'Z');
      S.crFrom(await MW.admin.proposeCampaign('ops-e2e-maker01', {
        scheduleAt: when,
        throttlePerSecond: 2,
        text: 'api offer ' + S.token,
        msisdns: ['01711000990'],
        reason: 'api campaign ' + S.token,
      }));
      await MW.admin.approve('ops-e2e-checker01', S.crId);
      await S.crIs('APPROVED');
    },
  },
];
