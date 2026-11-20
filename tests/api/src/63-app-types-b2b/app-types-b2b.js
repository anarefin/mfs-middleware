// Ticket 106 (FRS CH-APP-06, UC-07). B2B has no tariff row, so the fee is 0 and the debit is the amount.
// Folder 42 is the audit suite, so this is 63. Folder 48 (the SOAP facade) is not touched.
export default [
  {
    name: '[b2b] a distributor float transfer to an agent completes',
    run: async (MW, S) => {
      await S.note(['01612000001', '01812000001']);
      const pin = await MW.app.request('B2B_TRANSFER', '01812000001', 1000, MW.pin);
      const q = S.record(await MW.app.preview('seed-distributor01', 'b2b', {
        creditParty: { id: '01812000001' }, amount: { valueMinor: 1000, currency: 'BDT' },
      }));
      S.status(200);
      S.eq('fee 0', q.body.fee && q.body.fee.valueMinor, 0, String(q));
      const c = S.record(await MW.app.confirm('seed-distributor01', 'b2b', 'api-b2b-' + S.token, {
        previewId: q.body.previewId,
        creditParty: { id: '01812000001' },
        amount: { valueMinor: 1000, currency: 'BDT' },
        encryptedPin: pin.encryptedPin,
      }));
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01612000001': -1000, '01812000001': 1000 });
    },
  },
  {
    name: '[b2b] a customer cannot start a float transfer',
    run: async (MW, S) => {
      const q = S.record(await MW.app.preview('customer01', 'b2b', {
        creditParty: { id: '01811000001' }, amount: { valueMinor: 1000, currency: 'BDT' },
      }));
      S.status(403);
      S.code('MW-AUTH-002');
    },
  },
  {
    name: '[b2b] a partner app cannot start a float transfer',
    run: async (MW, S) => {
      const q = S.record(await MW.app.preview('seed-partner01', 'b2b', {
        creditParty: { id: '01812000001' }, amount: { valueMinor: 1000, currency: 'BDT' },
      }));
      S.status(403);
      S.code('MW-AUTH-002');
    },
  },
  {
    name: '[b2b] a DSO preview of a float transfer is allowed and moves nothing',
    run: async (MW, S) => {
      await S.note(['01512000001', '01812000001']);
      const q = S.record(await MW.app.preview('seed-dso01', 'b2b', {
        creditParty: { id: '01812000001' }, amount: { valueMinor: 1000, currency: 'BDT' },
      }));
      S.status(200);
      S.check('a previewId and no txnId', !!(q.body && q.body.previewId) && !q.body.txnId, String(q));
      await S.noChange();
    },
  },
];
