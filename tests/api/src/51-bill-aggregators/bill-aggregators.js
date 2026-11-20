// Ticket 114. Seeded bills (services/mw-simulators/src/main/resources/seed/v1/bills/<aggregator>.csv):
// AMBERIT (sslcommerz) 1140000001 1250.50 UNPAID, 1140000003 PAID, CARNIVAL 1140000011 3200.00 UNPAID;
// NESCO (ekpay) 1140000001 1250.50; DU (agg3, SOAP) 1140000001 1250.50; METLIFE (agg4) 1140000002 480.00.
// The bills are stateful: each scenario that pays one starts from the party reset of the simulator.
export default [
  {
    name: '[billpay] every bill aggregator answers a seeded bill in its own wire format',
    run: async (MW, S) => {
      const cases = [['AMBERIT', '1140000001', null, 125050], ['NESCO', '1140000001', '202611', 125050],
        ['DU', '1140000001', '202611', 125050], ['METLIFE', '1140000002', null, 48000]];
      for (const [biller, bill, month, minor] of cases) {
        const f = S.record(await MW.app.fetchBill('customer01', biller, bill, month));
        S.status(200);
        S.eq(biller + ' amount due', f.body.amount && f.body.amount.valueMinor, minor, String(f));
      }
      await S.noChange();
    },
  },
  {
    name: '[billpay] a bill the aggregator holds as paid is a mapped aggregator error',
    run: async (MW, S) => {
      S.record(await MW.app.fetchBill('customer01', 'AMBERIT', '1140000003', null));
      S.status(502);
      S.code('MW-PTR-SSLCOMMERZ-BILL_ALREADY_PAID');
      S.record(await MW.app.fetchBill('customer01', 'DU', '1140000003', '202611'));
      S.status(502);
      S.code('MW-PTR-AGG3-BILL_ALREADY_PAID');
    },
  },
  {
    name: '[billpay] a bill paid through sslcommerz completes and is then fetched as paid',
    run: async (MW, S) => {
      await MW.mock.reset('sslcommerz');
      await S.note(['01711000001', 'SETTLEMENT', 'FEE_INCOME']);
      const f = S.record(await MW.app.fetchBill('customer01', 'CARNIVAL', '1140000011', null));
      S.status(200);
      S.eq('the amount due is 320000 poisha', f.body.amount && f.body.amount.valueMinor, 320000, String(f));
      await S.billPay('customer01', 'CARNIVAL', '1140000011', 320000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.noHold('01711000001');
      S.record(await MW.app.fetchBill('customer01', 'CARNIVAL', '1140000011', null));
      S.status(502);
      S.code('MW-PTR-SSLCOMMERZ-BILL_ALREADY_PAID');
      await MW.mock.reset('sslcommerz');
    },
  },
];
