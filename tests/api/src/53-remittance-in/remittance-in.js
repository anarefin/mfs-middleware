// Tickets 119b/119c (FRS UC-16). The suite is the partner UCB IMT (MW.remit signs each request). A credit is
// paid from SETTLEMENT_UCB_IMT to the beneficiary's wallet; the partner learns the outcome from the status
// query. Partner references are unique per run, so a rerun on the same stack starts afresh.
// Helpers are copied into each script; a module-level binding is not.
export const helpers = [
  function books() {
    return ['01711000001', 'SETTLEMENT_UCB_IMT'];
  },
  function credited(amount) {
    return { '01711000001': amount, 'SETTLEMENT_UCB_IMT': -amount };
  },
  function reference(MW) {
    return 'API-REMIT-' + MW.uuid();
  },
  /** Polls the partner's status query until the remittance leaves ACCEPTED. */
  async function outcome(MW, S, partnerRef) {
    const r = await MW.poll(() => MW.remit.status(partnerRef), x => x.body && x.body.status && x.body.status !== 'ACCEPTED', 25000, 500);
    S.record(r);
    S.status(200, r);
    return r.body;
  },
];

export default [
  {
    name: '[remit] a beneficiary lookup answers the masked name and number of a customer wallet that can receive',
    run: async (MW, S) => {
      const msisdn = '01711000001';
      const r = S.record(await MW.remit.lookup(msisdn));
      S.status(200, r);
      S.check('the name is the first initial and stars', /^\S\*+$/.test(r.body.maskedName || ''), r.body);
      S.eq('the number is masked', r.body.maskedMsisdn, msisdn.slice(0, 4) + '****' + msisdn.slice(-3));
      S.eq('the wallet can receive', r.body.eligible, true);
      S.check('nothing else about the wallet is told', Object.keys(r.body).sort().join() === 'eligible,maskedMsisdn,maskedName', r.body);
      S.check('no number in the clear', !r.raw.includes(msisdn), r.raw);
    },
  },
  {
    name: '[remit] a lookup of a number without a customer wallet is 404 and one signed with the wrong secret is 401',
    run: async (MW, S) => {
      const unknown = S.record(await MW.remit.lookup('01799999990'));
      S.status(404, unknown);
      S.eq('not found', unknown.body.code, 'MW-VAL-003');
      const forged = S.record(await MW.remit.lookup('01711000001', { secret: 'not-the-partner-secret' }));
      S.status(401, forged);
      S.eq('signature refused', forged.body.code, 'MW-AUTH-001');
    },
  },
  {
    name: '[remit frs] T1 a remittance to a customer credits the wallet once from the remittance settlement wallet and the partner sees CREDITED',
    run: async (MW, S) => {
      await S.note(books());
      const ref = reference(MW);
      const r = S.record(await MW.remit.credit(ref, '01711000001', '250.00'));
      S.status(202, r);
      S.eq('accepted', r.body.status, 'ACCEPTED');
      const done = await outcome(MW, S, ref);
      S.eq('credited', done.status, 'CREDITED');
      S.eq('the same Upay reference', done.upayRef, r.body.upayRef);
      await S.changedBy(credited(25000));
    },
  },
  {
    name: '[remit frs] T6 the same partner reference twice credits once and answers the same Upay reference; a changed amount is 409',
    run: async (MW, S) => {
      await S.note(books());
      const ref = reference(MW);
      const first = S.record(await MW.remit.credit(ref, '01711000001', '260.00'));
      S.status(202, first);
      const again = S.record(await MW.remit.credit(ref, '01711000001', '260.00'));
      S.status(200, again);
      S.eq('a duplicate', again.body.duplicate, true);
      S.eq('the same Upay reference', again.body.upayRef, first.body.upayRef);
      const changed = S.record(await MW.remit.credit(ref, '01711000001', '261.00'));
      S.status(409, changed);
      S.eq('idempotency conflict', changed.body.code, 'MW-IDEM-002');
      S.eq('credited', (await outcome(MW, S, ref)).status, 'CREDITED');
      await S.changedBy(credited(26000));
    },
  },
  {
    name: '[remit frs] T9 a remittance over the 2,00,000 Tk per-transaction limit is rejected and moves nothing',
    run: async (MW, S) => {
      await S.note(books());
      const ref = reference(MW);
      const r = S.record(await MW.remit.credit(ref, '01711000001', '200000.01'));
      S.status(202, r);
      S.eq('rejected', (await outcome(MW, S, ref)).status, 'REJECTED');
      await S.noChange();
    },
  },
];
