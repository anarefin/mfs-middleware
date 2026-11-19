// Ticket 76: the transaction authorisation assertion (tickets 71-72, spec 18.7, D22) black-box. A
// channel sends the PIN ciphertext to mw-auth (POST /internal/v1/txn-authorisations, mTLS) with the
// exact values it then publishes; mw-auth checks the PIN (lockout included) and signs an ES256
// assertion bound to the transaction's hash, which the core checks; no PIN material goes on Kafka.
// The app scenarios pay as customer01 (the run customer) and always end with a right PIN, which
// resets its wrong-PIN count; the USSD lockout takes a fresh wallet. Expiry: folder 90.
export default [
  {
    name: '[assertion p2p] a wrong PIN is refused by mw-auth before the saga: the orchestrator never sees the request',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.p2p('customer01', '01711000002', 1000, MW.WRONG_PIN);
      S.status(401);
      S.code('MW-AUTH-040');
      const handled = await MW.testApi.handled(S.idem);
      S.eq('no request with that idempotency key reached mw-orchestrator', handled.status, 404, String(handled));
      await S.noChange();
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
    },
  },
  {
    // the assertion of an idempotency key lives 60 s in mw-auth (Valkey assert:{wallet}:{key}): a resend
    // of the same transaction gets it back without a second PIN check, the orchestrator answers the
    // stored transaction (the assertion is not part of its request hash)
    name: '[assertion p2p] a resend with the same idempotency key inside the assertion life reuses it: same txnId, money moves once',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.submit('customer01', await MW.app.request('P2P', '01711000002', 1000, MW.WRONG_PIN), S.idem);
      S.status(200);
      S.sameTxnId();
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435 });
      // another transaction under the live key: mw-auth refuses to sign it
      await S.submit('customer01', await MW.app.request('P2P', '01711000002', 2000, MW.pin), S.idem);
      S.status(409);
      S.code('MW-IDEM-001');
    },
  },
  {
    name: '[assertion ussd lock] USSD asks mw-auth for the assertion: wrong PINs are asked again, the third locks the PIN and ends the session',
    run: async (MW, S) => {
      const w = await MW.auth.fresh(S, '48207');
      await S.note([w.msisdn, '01711000002']);
      const wrong = MW.WRONG_PIN;
      await S.dial('robi', w.msisdn, '*269#', '9, 1, 01711000002, 10, 1, ' + wrong);
      S.check('mw-auth refused the PIN and it is asked again (2 tries left)', S.session.ended === false
        && (S.session.screens[S.session.screens.length - 1].message || '').startsWith('Wrong PIN. 2 tries left.'), S.session.screens);
      // the second and the third wrong PIN of the wallet (MW_AUTH_LOCK_TRIES=3): the third locks
      await S.dial('robi', w.msisdn, '*269#', '9, 1, 01711000002, 10, 1, ' + wrong + ', ' + wrong);
      S.ussdEnds('Your PIN is locked for now. Please try again later.');
      await S.dial('robi', w.msisdn, '*269#', '9, 1, 01711000002, 10, 1, 48207');
      S.ussdEnds('Your PIN is locked for now. Please try again later.');
      await S.noChange();
      // the app login of the wallet is locked too: one PIN lock for every channel
      S.record(await MW.auth.login(w.d, w.msisdn, { pin: '48207' }));
      S.status(423);
      S.code('MW-AUTH-041');
    },
  },
  {
    name: '[assertion checkout] a wrong PIN at checkout is refused by mw-auth and the session stays open; a new OTP and the right PIN pay once',
    run: async (MW, S) => {
      await S.note(['01711000001', '01911000001']);
      const co = await checkoutSession(MW, S, 1800);
      await co.otp('01711000001');
      S.record(await MW.checkout.confirm(co.token, co.code, MW.WRONG_PIN));
      S.status(401);
      S.code('MW-AUTH-040');
      const page = await MW.checkout.page(co.token);
      S.eq('the session is still OPEN', page.body && page.body.status, 'OPEN', String(page));
      await S.noChange();
      // the OTP was spent by its check (before the PIN): the payer asks a new one
      S.record(await MW.checkout.confirm(co.token, co.code, MW.pin));
      S.status(401);
      S.code('MW-AUTH-011');
      await co.otp('01711000001');
      S.record(await MW.checkout.confirm(co.token, co.code, MW.pin));
      S.status(200);
      S.eq('the checkout result is PAID', S.last().body.status, 'PAID');
      await S.changedBy({ '01711000001': -1800, '01911000001': 1800 });
    },
  },
];

// A merchant session of the dev merchant (as folder 09's helper): create() then otp(wallet).
async function checkoutSession(MW, S, amount) {
  const orderId = 'API-' + MW.uuid();
  const secret = MW.v('checkoutSecret');
  const r = S.record(await MW.checkout.create(MW.checkout.sessionBody(orderId, amount), secret));
  S.eq('session created OPEN (201)', [r.status, r.body && r.body.status], [201, 'OPEN'], String(r));
  const co = { orderId, amount, sessionId: r.body.sessionId, token: r.body.checkoutUrl.slice(r.body.checkoutUrl.lastIndexOf('/') + 1) };
  const taka = (Math.floor(amount / 100)) + '.' + String(amount % 100).padStart(2, '0');
  const re = /Your Bits code is ([0-9]{6}) to pay Tk ([0-9.]+) to Karim Traders/;
  const codes = async w => (await MW.sim.sms(w)).map(m => re.exec(m.text || '')).filter(m => m && m[2] === taka).map(m => m[1]);
  co.otp = async wallet => {
    const before = await codes(wallet);
    const o = S.record(await MW.checkout.otp(co.token, wallet));
    S.status(202, o);
    const fresh = await MW.poll(async () => (await codes(wallet)).filter(c => !before.includes(c)), l => l.length > 0, 30000, 700);
    S.eq('one new OTP SMS to ' + wallet, fresh.length, 1, fresh);
    co.code = fresh[0];
  };
  return co;
}

export const helpers = [checkoutSession];
