// Ticket 116: merchant refunds of a web checkout payment (FRS CH-WEB-03, FL-06, UC-12). customer01
// pays Karim Traders on the hosted page API, then the merchant refunds the payment, whole or in part.
// Money returns to the payer's wallet; the refunded total never passes the payment.
export default [
  {
    name: '[checkout frs] T1 two partial refunds return the money to the payer and each sends a signed refund IPN',
    run: async (MW, S) => {
      const co = await paidCheckout(MW, S, 3000);
      await S.note(['01711000001', '01911000001']);
      const first = S.record(await MW.checkout.refund(co.sessionId, 'api-' + MW.uuid(), 1000, 'item returned'));
      S.status(201);
      S.eq('the first refund is its own completed transaction of the payment',
        [first.body.status, first.body.amount.valueMinor, first.body.originalTxnId, first.body.refundId !== co.txnId],
        ['COMPLETED', 1000, co.txnId, true], String(first));
      await S.changedBy({ '01711000001': 1000, '01911000001': -1000 });
      const second = S.record(await MW.checkout.refund(co.sessionId, 'api-' + MW.uuid(), 2000));
      S.status(201);
      await S.changedBy({ '01711000001': 3000, '01911000001': -3000 });
      const list = S.record(await MW.checkout.refunds(co.sessionId));
      S.status(200);
      S.eq('the merchant lists both refunds and the refunded total', [list.body.items.length, list.body.refunded.valueMinor], [2, 3000], String(list));
      const secret = MW.v('checkoutSecret');
      for (const r of [first.body, second.body]) {
        const got = await MW.poll(() => MW.sim.refundIpnReceived(r.refundId), l => l.length > 0, 30000, 1000);
        S.eq('one refund IPN for ' + r.refundId, got.length, 1, got);
        S.check('the refund IPN signature verifies', MW.checkout.verifyIpn(got[0], secret), got[0]);
        const b = JSON.parse(got[0].body);
        S.eq('refund IPN body: type, refund, session, status, amount', [b.type, b.refundId, b.sessionId, b.status, b.amount.valueMinor],
          ['REFUND', r.refundId, co.sessionId, 'COMPLETED', r.amount.valueMinor]);
      }
    },
  },
  {
    name: '[checkout frs] T2 a refund past the payment is refused with MW-CORE-REFUND_EXCEEDS and moves nothing',
    run: async (MW, S) => {
      const co = await paidCheckout(MW, S, 1500);
      S.record(await MW.checkout.refund(co.sessionId, 'api-' + MW.uuid()));
      S.status(201);
      S.eq('no amount refunds the whole payment', S.last().body.amount.valueMinor, 1500);
      await S.note(['01711000001', '01911000001']);
      S.record(await MW.checkout.refund(co.sessionId, 'api-' + MW.uuid(), 100));
      S.status(422);
      S.code('MW-CORE-REFUND_EXCEEDS');
      await S.noChange();
      const list = await MW.checkout.refunds(co.sessionId);
      S.eq('the refused refund is listed REJECTED; the refunded total stays the payment',
        [list.body.items.map(i => i.status), list.body.refunded.valueMinor], [['COMPLETED', 'REJECTED'], 1500], String(list));
    },
  },
  {
    name: '[checkout frs] T6 the same Idempotency-Key twice refunds once and another body is MW-IDEM-001',
    run: async (MW, S) => {
      const co = await paidCheckout(MW, S, 2000);
      await S.note(['01711000001', '01911000001']);
      const key = 'api-' + MW.uuid();
      const first = S.record(await MW.checkout.refund(co.sessionId, key, 500));
      S.status(201);
      const again = S.record(await MW.checkout.refund(co.sessionId, key, 500));
      S.status(200);
      S.eq('the repeat answers the same refund', again.body.refundId, first.body.refundId);
      S.record(await MW.checkout.refund(co.sessionId, key, 600));
      S.status(409);
      S.code('MW-IDEM-001');
      await S.changedBy({ '01711000001': 500, '01911000001': -500 });
    },
  },
  {
    name: '[checkout frs] T9 a session that was never paid is not refunded and nothing moves',
    run: async (MW, S) => {
      await S.note(['01711000001', '01911000001']);
      const orderId = 'API-' + MW.uuid();
      const r = S.record(await MW.checkout.create(MW.checkout.sessionBody(orderId, 900), MW.v('checkoutSecret')));
      S.status(201);
      S.record(await MW.checkout.refund(r.body.sessionId, 'api-' + MW.uuid(), 100));
      S.status(409);
      S.code('MW-VAL-021');
      await S.noChange();
    },
  },
  {
    name: '[checkout frs] T10 a refund keeps the ledger identity: what the merchant gives back the payer gets',
    run: async (MW, S) => {
      const co = await paidCheckout(MW, S, 2500);
      await S.note(['01711000001', '01911000001']);
      S.record(await MW.checkout.refund(co.sessionId, 'api-' + MW.uuid(), 1234));
      S.status(201);
      await S.changedBy({ '01711000001': 1234, '01911000001': -1234 });
      const d = await S.deltas();
      S.eq('the refund moves money between the two wallets only (sum 0)', d['01711000001'] + d['01911000001'], 0, d);
    },
  },
];

// Shared steps of the folder (inlined into each script by the builder): a checkout session of
// customer01 paid on the hosted page API with the OTP from the SMSC simulator and the PIN.
async function paidCheckout(MW, S, amount) {
  const orderId = 'API-' + MW.uuid();
  const r = S.record(await MW.checkout.create(MW.checkout.sessionBody(orderId, amount), MW.v('checkoutSecret')));
  S.eq('session created OPEN (201)', [r.status, r.body && r.body.status], [201, 'OPEN'], String(r));
  const token = r.body.checkoutUrl.slice(r.body.checkoutUrl.lastIndexOf('/') + 1);
  const taka = (Math.floor(amount / 100)) + '.' + String(amount % 100).padStart(2, '0');
  const re = /Your Bits code is ([0-9]{6}) to pay Tk ([0-9.]+) to Karim Traders/;
  const wallet = '01711000001';
  const codes = async () => (await MW.sim.sms(wallet)).map(m => re.exec(m.text || '')).filter(m => m && m[2] === taka).map(m => m[1]);
  const before = await codes();
  S.status(202, S.record(await MW.checkout.otp(token, wallet)));
  const fresh = await MW.poll(async () => (await codes()).filter(c => !before.includes(c)), l => l.length > 0, 30000, 700);
  S.eq('one new OTP SMS', fresh.length, 1, fresh);
  const paid = S.record(await MW.checkout.confirm(token, fresh[0], MW.pin));
  S.eq('the payment is PAID', [paid.status, paid.body && paid.body.status], [200, 'PAID'], String(paid));
  return { orderId, amount, sessionId: r.body.sessionId, txnId: paid.body.txnId };
}
export const helpers = [paidCheckout];
