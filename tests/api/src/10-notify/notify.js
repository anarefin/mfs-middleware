// Port of notify.feature: mw-notify sends TXN_P2P_SENT / TXN_P2P_RECEIVED over SMPP to the SMSC
// simulator (bn by default, UCS-2, long text in parts), delivered (DLR DELIVRD).
export default [
  {
    name: '[p2p notify] sender and receiver get the right SMS text',
    run: async (MW, S) => {
      await S.p2p('customer01', '01711000002', 1000, '1234');
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.sms('01711000001', 'আপনি 0171****002 নম্বরে ৳10.00 পাঠিয়েছেন। ফি ৳5.00। TrxID {txnId}');
      await S.sms('01711000002', 'আপনি 0171****001 নম্বর থেকে ৳10.00 পেয়েছেন। TrxID {txnId}');
      const id = S.txnId();
      const mine = (await MW.sim.sms('01711000001')).filter(m => (m.text || '').includes(id));
      S.eq('the SMS to 01711000001 was sent as UCS2 in 2 parts', mine.map(m => [m.encoding, m.segments]), [['UCS2', 2]]);
    },
  },
];
