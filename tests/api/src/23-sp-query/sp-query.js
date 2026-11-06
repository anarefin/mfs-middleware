// Port of sp-query.feature. The org sees what it initiated (INITIATOR) and what it collects as payee
// (PAYEE): in the dev stack ORG002 collects for biller DESCO and merchant wallet 01911000001.
export default [
  {
    name: '[sp-query bulk] an org sees its batch children with their timeline, another org sees none of them',
    run: async (MW, S) => {
      await MW.portal.idle('org-checker01');
      const up = S.record(await MW.portal.upload('org-maker01', 'msisdn,amount,reference\n01711000004,1.00,SPQ-' + MW.unique() + '\n'));
      S.status(201);
      const id = up.body.batchId;
      S.record(await MW.portal.approve('org-checker01', id));
      const done = await MW.poll(() => MW.portal.get('org-checker01', id), r => r.body && r.body.status === 'COMPLETED', 120000, 1000);
      S.eq('within 2 minutes the batch is COMPLETED', done.body.status, 'COMPLETED');
      const list = S.record(await MW.portal.txns('org-checker01', id));
      S.status(200);
      const items = list.body.items || [];
      S.eq('1 COMPLETED BULK_DISBURSE transaction paying 0171****004 of the batch',
        items.map(t => [t.status, t.txnType, t.creditParty.id, t.attributes && t.attributes.batchId, t.role]), [['COMPLETED', 'BULK_DISBURSE', '0171****004', id, 'INITIATOR']]);
      const first = items[0].txnId;
      const open = S.record(await MW.portal.txn('org-maker01', first));
      S.status(200);
      const chain = [];
      (open.body.timeline || []).forEach(s => { if (!chain.length) chain.push(s.from); chain.push(s.to); });
      S.check('the timeline ends RECEIVED -> VALIDATING -> POSTING -> COMPLETED', chain.join(' -> ').endsWith('RECEIVED -> VALIDATING -> POSTING -> COMPLETED'), chain);
      S.record(await MW.portal.txn('org-checker02', first));
      S.status(404);
      S.code('MW-VAL-003');
      S.record(await MW.portal.txns('org-checker02', id));
      S.status(404);
    },
  },
  {
    name: '[sp-query billpay] the org a biller belongs to sees the bill payments it collects, tagged PAYEE',
    run: async (MW, S) => {
      await S.billPay('customer01', 'DESCO', '1234567', 66700, '1234');
      S.status(200);
      S.txnStatus('COMPLETED');
      const id = S.txnId();
      const l2 = S.record(await MW.portal.txns('org-checker02'));
      S.status(200);
      const t = (l2.body.items || []).find(x => x.txnId === id);
      S.check("the org's transaction list has the transaction tagged PAYEE", !!t && t.role === 'PAYEE', t || l2.body);
      const open = S.record(await MW.portal.txn('org-checker02', id));
      S.status(200);
      S.eq('the opened transaction is tagged PAYEE', open.body.transaction.role, 'PAYEE');
      const chain = [];
      (open.body.timeline || []).forEach(s => { if (!chain.length) chain.push(s.from); chain.push(s.to); });
      S.check('the timeline ends COMMITTING -> COMPLETED', chain.join(' -> ').endsWith('COMMITTING -> COMPLETED'), chain);
      const l1 = S.record(await MW.portal.txns('org-checker01'));
      S.check("ORG001's list does not have the transaction", !(l1.body.items || []).some(x => x.txnId === id));
      S.record(await MW.portal.txn('org-checker01', id));
      S.status(404);
    },
  },
  {
    name: '[sp-query] a portal user without an org role may not query',
    run: async (MW, S) => {
      S.record(await MW.portal.txns('sp-viewer01'));
      S.status(403);
    },
  },
];
