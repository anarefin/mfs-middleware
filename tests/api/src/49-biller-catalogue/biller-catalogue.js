// Ticket 113c (FRS FL-04, FL-05). Dev seed (services/mw-admin-api/src/main/resources/biller-seeds/dev/billers.csv):
// AMBERIT is MINIMUM 10000 poisha, DESCO_PRE is OPEN 10000..5000000 poisha. A refusal comes from
// mw-orchestrator before validate, so nothing is held and no money moves.
export default [
  {
    name: '[billpay] the app biller list comes from the catalogue and shows its MINIMUM and OPEN billers',
    run: async (MW, S) => {
      const b = S.record(await MW.app.billers('customer01'));
      S.status(200);
      const billers = (b.body || {}).billers || [];
      S.check('the catalogue billers are listed', billers.length >= 57, String(b));
      S.check('AMBERIT is listed', billers.some(x => x.code === 'AMBERIT'), String(b));
      S.check('DESCO_PRE is listed', billers.some(x => x.code === 'DESCO_PRE'), String(b));
    },
  },
  {
    name: '[billpay] a FIXED biller refuses an amount other than the bill the app fetched before any hold',
    run: async (MW, S) => {
      // Ticket 113b: the gateway keeps the fetched amount (66700 poisha for a bill ending 567) and the
      // confirm attaches it; the client cannot set it. DESCO is FIXED in the dev seed.
      await S.note(['01711000001', 'SETTLEMENT', 'FEE_INCOME']);
      const f = S.record(await MW.app.fetchBill('customer01', 'DESCO', '3234567', '202611'));
      S.status(200);
      await S.previewBillPay('customer01', 'DESCO', '3234567', 66000, MW.pin);
      S.status(400);
      S.code('MW-VAL-032');
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
  {
    name: '[billpay] a MINIMUM biller refuses an amount below its minimum before any hold',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE_INCOME']);
      await S.previewBillPay('customer01', 'AMBERIT', 'AMB-12345', 9999, MW.pin);
      S.status(400);
      S.code('MW-VAL-032');
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
  {
    name: '[billpay] an OPEN biller refuses an amount below its range before any hold',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE_INCOME']);
      await S.previewBillPay('customer01', 'DESCO_PRE', '12345678', 9999, MW.pin);
      S.status(400);
      S.code('MW-VAL-032');
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
];
