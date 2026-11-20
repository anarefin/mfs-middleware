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
