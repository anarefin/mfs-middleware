// Ticket 105 (FRS CH-APP-05, PLT-026). P2P of 10 BDT: core-mock tariff fee 5 BDT (500 poisha), VAT 65
// (15% inside the fee), commission 0, payer debited 15 BDT, FEE_INCOME 435. The payee customer02
// (Ayesha Siddiqua) comes back masked. One request per older folder still uses POST /app/v1/transactions.
export default [
  {
    name: '[p2p] a preview shows the fee, VAT, commission and the masked payee, and confirm debits that fee',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.previewP2p('customer01', '01711000002', 1000, MW.pin);
      const q = S.quote;
      S.eq('preview HTTP 200', q.status, 200, String(q));
      S.eq('fee 500 poisha', q.body.fee && q.body.fee.valueMinor, 500, String(q));
      S.eq('VAT 65 poisha', q.body.vat && q.body.vat.valueMinor, 65, String(q));
      S.eq('commission 0', q.body.commission && q.body.commission.valueMinor, 0, String(q));
      S.eq('total is amount plus fee', q.body.total && q.body.total.valueMinor, 1500, String(q));
      S.eq('payee masked', q.body.counterparty && q.body.counterparty.id, '0171****002', String(q));
      S.eq('name to the initial', q.body.counterparty && q.body.counterparty.name, 'A.', String(q));
      S.check('the limits result is present', !!(q.body.limits && q.body.limits.remaining), String(q));
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435 });
    },
  },
  {
    name: '[p2p] a preview alone moves no money',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      const q = S.record(await MW.app.preview('customer01', 'send-money', {
        creditParty: { id: '01711000002' }, amount: { valueMinor: 1000, currency: 'BDT' },
      }));
      S.status(200);
      S.check('a previewId and no txnId', !!(q.body && q.body.previewId) && !q.body.txnId, String(q));
      await S.noChange();
    },
  },
  {
    name: '[p2p] a confirm whose amount differs from the preview is refused',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      const q = S.record(await MW.app.preview('customer01', 'send-money', {
        creditParty: { id: '01711000002' }, amount: { valueMinor: 1000, currency: 'BDT' },
      }));
      S.status(200);
      const pin = await MW.app.request('P2P', '01711000002', 1001, MW.pin);
      const c = S.record(await MW.app.confirm('customer01', 'send-money', 'api-mismatch-' + S.token, {
        previewId: q.body.previewId,
        creditParty: { id: '01711000002' },
        amount: { valueMinor: 1001, currency: 'BDT' },
        encryptedPin: pin.encryptedPin,
      }));
      S.eq('HTTP status 400', c.status, 400, String(c));
      S.code('MW-VAL-026');
      await S.noChange();
    },
  },
  {
    name: '[p2p] an amount over the per-transaction limit is refused at preview and moves nothing',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      const q = S.record(await MW.app.preview('customer01', 'send-money', {
        creditParty: { id: '01711000002' }, amount: { valueMinor: 600000, currency: 'BDT' },
      }));
      S.status(422);
      S.code('MW-CORE-LIMIT_EXCEEDED_PER_TXN');
      S.check('no txnId', !(q.body && q.body.txnId), String(q));
      await S.noChange();
    },
  },
];
