// Port of checkout.feature. The dev merchant (hmac-auth consumer merchant_demo, wallet 01911000001
// "Demo Shop") creates a session; customer01 pays on the hosted page API with the OTP read from the
// SMSC simulator and the PIN; the merchant verifies the signed redirect and the signed IPN.
// Each scenario uses S.co (its session): create(), otp(), confirm(), redirected(), sees().
// The Cucumber step "the OTP was sent with OTP priority" read notify.message with psql; no API
// exposes a message's priority, so it is dropped here (mw-notify's DispatchService tests cover it).
export default [
  {
    name: '[checkout] create session, pay with OTP and PIN, signed redirect the merchant verifies',
    run: async (MW, S) => {
      await S.note(['01711000001', '01911000001']);
      const co = await checkoutSession(MW, S, 2500);
      const page = await MW.checkout.page(co.token);
      const key = await MW.http('GET', '/test/core-mock/v1/pin-public-key', { apikey: true });
      S.eq('the checkout page shows 2500 poisha for Demo Shop with the core PIN key',
        [page.status, page.body.merchantName, page.body.amount.valueMinor, page.body.pinKey.keyId], [200, 'Demo Shop', 2500, key.body.keyId]);
      await co.otp('01711000001');
      S.record(await MW.checkout.confirm(co.token, co.code === '000000' ? '999999' : '000000', '1234'));
      S.status(401);
      S.code('MW-AUTH-010');
      await S.noChange();
      S.record(await MW.checkout.confirm(co.token, co.code, '1234'));
      S.status(200);
      S.eq('the checkout result is PAID', S.last().body.status, 'PAID');
      await S.changedBy({ '01711000001': -2500, '01911000001': 2500 });
      await co.redirected('ok', 'PAID');
      await co.sees('PAID', '0171****001');
    },
  },
  {
    name: '[checkout] a payment the core rejects fails the session and sends the browser to the fail URL',
    run: async (MW, S) => {
      await S.note(['01711000001', '01911000001']);
      const co = await checkoutSession(MW, S, 1500);
      await S.reject('ValidateTransaction', 'INSUFFICIENT_BALANCE');
      await co.otp('01711000001');
      S.record(await MW.checkout.confirm(co.token, co.code, '1234'));
      S.status(200);
      S.eq('the checkout result is FAILED', S.last().body.status, 'FAILED');
      await S.noChange();
      await co.redirected('fail', 'FAILED');
      await co.sees('FAILED');
    },
  },
  {
    name: "[checkout ipn] the merchant's IPN endpoint fails twice, then the signed IPN is delivered once",
    run: async (MW, S) => {
      const co = await checkoutSession(MW, S, 1200);
      S.after(() => MW.sim.ipnAcceptAll());
      await MW.sim.ipnFail(co.sessionId, 2, 503);
      await co.otp('01711000001');
      S.record(await MW.checkout.confirm(co.token, co.code, '1234'));
      S.status(200);
      S.eq('the checkout result is PAID', S.last().body.status, 'PAID');
      const got = await MW.poll(() => MW.sim.ipnReceived(co.sessionId), l => l.length >= 3, 90000, 1000);
      S.eq('the merchant received the IPN 3 times, answering 503, 503, 200', got.map(r => r.answeredStatus), [503, 503, 200]);
      S.eq('attempts 1, 2, 3', got.map(r => Number((r.headers || {})['x-bits-attempt'])), [1, 2, 3]);
      const secret = MW.v('checkoutSecret');
      const accepted = got.filter(r => Math.floor(r.answeredStatus / 100) === 2);
      S.eq('exactly one IPN accepted', accepted.length, 1);
      const ipn = accepted[0];
      S.check('every IPN carries a valid signature', got.every(r => MW.checkout.verifyIpn(r, secret)), got);
      S.check('a tampered IPN fails the check', !MW.checkout.verifyIpn(Object.assign({}, ipn, { body: ipn.body.replace('PAID', 'X') }), secret));
      S.check('the same body on every attempt', got.every(r => r.body === ipn.body));
      const b = JSON.parse(ipn.body);
      S.eq('IPN body: session, order, PAID, amount, txnId', [b.sessionId, b.orderId, b.status, b.amount.valueMinor, !!b.txnId], [co.sessionId, co.orderId, 'PAID', 1200, true]);
      // negative window: with TIMERS=fast a further retry would come within 1-3 s (Cucumber: 15 s)
      const before = got.length;
      const later = await MW.poll(() => MW.sim.ipnReceived(co.sessionId), l => l.length > before, 5000, 1000);
      S.eq('no further IPN of the session within 5 seconds', later.length, before);
    },
  },
  {
    name: '[checkout] a second confirmation of a paid session is refused and pays nothing again',
    run: async (MW, S) => {
      await S.note(['01711000001', '01911000001']);
      const co = await checkoutSession(MW, S, 1300);
      await co.otp('01711000001');
      S.record(await MW.checkout.confirm(co.token, co.code, '1234'));
      S.status(200);
      S.eq('the checkout result is PAID', S.last().body.status, 'PAID');
      S.record(await MW.checkout.confirm(co.token, co.code, '1234'));
      S.status(409);
      S.code('MW-VAL-021');
      await S.changedBy({ '01711000001': -1300, '01911000001': 1300 });
      await co.sees('PAID', '0171****001');
    },
  },
  {
    name: '[checkout tsq] a checkout payment whose core reply is lost is found by QueryTransaction and paid',
    run: async (MW, S) => {
      await S.note(['01711000001', '01911000001']);
      const co = await checkoutSession(MW, S, 1400);
      await S.dropReply('PostTransfer');
      await co.otp('01711000001');
      S.record(await MW.checkout.confirm(co.token, co.code, '1234'));
      S.status(202);
      await co.seesWithin('PAID', 120);
      await S.changedBy({ '01711000001': -1400, '01911000001': 1400 });
      await co.redirected('ok', 'PAID');
    },
  },
  {
    name: '[checkout tsq] a checkout payment whose core refusal is lost is found by QueryTransaction and fails',
    run: async (MW, S) => {
      await S.note(['01711000001', '01911000001']);
      const co = await checkoutSession(MW, S, 1600);
      await S.refusalLost('PostTransfer');
      await co.otp('01711000001');
      S.record(await MW.checkout.confirm(co.token, co.code, '1234'));
      S.status(202);
      await co.seesWithin('FAILED', 120);
      await S.noChange();
      await co.redirected('fail', 'FAILED');
    },
  },
  {
    name: '[checkout] a merchant request with a bad HMAC signature is refused at the edge',
    run: async (MW, S) => {
      S.record(await MW.checkout.create(MW.checkout.sessionBody('API-' + MW.uuid(), 1000), 'not-the-merchant-secret'));
      S.status(401);
      S.code('MW-AUTH-001');
    },
  },
  {
    name: '[checkout] a merchant request without a signature is refused at the edge',
    run: async (MW, S) => {
      S.record(await MW.checkout.create(MW.checkout.sessionBody('API-' + MW.uuid(), 1000), null));
      S.status(401);
      S.code('MW-AUTH-001');
    },
  },
];

// Shared steps of the folder. A run function is serialized on its own, so this helper is inlined
// into each script by the builder (see build_collection.mjs, `helpers`).
async function checkoutSession(MW, S, amount) {
  const orderId = 'API-' + MW.uuid();
  const secret = MW.v('checkoutSecret');
  const r = S.record(await MW.checkout.create(MW.checkout.sessionBody(orderId, amount), secret));
  S.eq('session created OPEN (201)', [r.status, r.body && r.body.status], [201, 'OPEN'], String(r));
  const co = { orderId, amount, sessionId: r.body.sessionId, token: r.body.checkoutUrl.slice(r.body.checkoutUrl.lastIndexOf('/') + 1) };
  const taka = (Math.floor(amount / 100)) + '.' + String(amount % 100).padStart(2, '0');
  const re = /Your Bits code is ([0-9]{6}) to pay Tk ([0-9.]+) to Demo Shop/;
  const codes = async w => (await MW.sim.sms(w)).map(m => re.exec(m.text || '')).filter(m => m && m[2] === taka).map(m => m[1]);
  co.otp = async wallet => {
    const before = await codes(wallet);
    const o = S.record(await MW.checkout.otp(co.token, wallet));
    S.status(202, o);
    const fresh = await MW.poll(async () => (await codes(wallet)).filter(c => !before.includes(c)), l => l.length > 0, 30000, 700);
    S.eq('one new OTP SMS to ' + wallet, fresh.length, 1, fresh);
    co.code = fresh[0];
  };
  co.redirected = async (which, status) => {
    const b = await MW.checkout.back(co.token);
    const loc = b.header('Location') || '';
    S.eq('303 to the merchant ' + which + ' URL', [b.status, loc.startsWith('https://shop.example.test/checkout/' + which)], [303, true], String(b) + ' ' + loc);
    const q = MW.checkout.query(loc);
    S.eq('result status, order, session, amount', [q.status, q.orderId, q.sessionId, q.amount], [status, co.orderId, co.sessionId, String(co.amount)]);
    S.check('the merchant verifies the signed result', MW.checkout.verifyResult(q, secret), q);
    S.check('a tampered result fails the check', !MW.checkout.verifyResult(Object.assign({}, q, { status: status === 'PAID' ? 'FAILED' : 'PAID' }), secret));
    if (status === 'PAID') {
      S.check('PAID result keeps cart=7 and names the txnId', q.cart === '7' && !!q.txnId, q);
      const confirmed = S.last().body && S.last().body.redirectUrl;
      if (confirmed) S.check('the confirm answer redirectUrl verifies too', MW.checkout.verifyResult(MW.checkout.query(confirmed), secret), confirmed);
    }
  };
  co.sees = async (status, payer) => {
    const g = await MW.checkout.get(co.sessionId);
    S.eq('the merchant sees the session as ' + status, [g.status, g.body && g.body.status], [200, status], String(g));
    if (payer) S.eq('paid by ' + payer + ' with a txnId', [g.body.payer, !!g.body.txnId], [payer, true]);
  };
  co.seesWithin = async (status, seconds) => {
    const g = await MW.poll(() => MW.checkout.get(co.sessionId), x => x.body && x.body.status === status, seconds * 1000, 1000);
    S.eq('the merchant sees the session as ' + status, g.body && g.body.status, status, String(g));
  };
  return co;
}
export const helpers = [checkoutSession];
