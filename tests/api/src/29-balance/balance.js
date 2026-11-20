// ADR D26: the balance check. customer01 is the run customer; the expected figure is core-mock's
// available balance (balance minus open holds), read through the test API just before the check.
export const helpers = [
  // poisha -> "12,000.00" as the USSD screen shows it
  function tk(minor) {
    const s = String(Math.floor(minor / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    return s + '.' + String(minor % 100).padStart(2, '0');
  },
];

export default [
  {
    name: '[balance ussd] option 5 asks for the PIN and shows the available balance in English; no money moves',
    run: async (MW, S) => {
      await S.note(['01711000001']);
      const available = (await MW.core.wallet('01711000001')).availableMinor;
      await S.dial('robi', '01711000001', '*269#', '9, 5, ' + MW.pin);
      S.ussdScreens([
        'Bits\n1 সেন্ড মানি\n2 ক্যাশ আউট\n3 রিচার্জ\n4 বিল পে\n0. পরবর্তী\n00. পেছনে',
        'Bits\n1 Send Money\n2 Cash Out\n3 Recharge\n4 Pay Bill\n0. Next\n00. Back',
        'Enter your PIN',
      ]);
      S.ussdEnds('Your balance is Tk ' + tk(available));
      await S.noChange();
    },
  },
  {
    name: '[balance ussd] the balance check in Bangla shows the same figure',
    run: async (MW, S) => {
      const available = (await MW.core.wallet('01711000001')).availableMinor;
      await S.dial('robi', '01711000001', '*269#', '5, ' + MW.pin);
      S.ussdEnds('আপনার ব্যালেন্স ৳' + tk(available));
    },
  },
  {
    name: '[balance assertion ussd] a wrong PIN on the balance check is asked again and shows no balance',
    run: async (MW, S) => {
      await S.dial('robi', '01711000001', '*269#', '9, 5, ' + MW.WRONG_PIN);
      const last = S.session.screens[S.session.screens.length - 1];
      S.check('the session stays open on the PIN retry', S.session.ended === false && (last.message || '').startsWith('Wrong PIN.'), S.session.screens);
    },
  },
  {
    name: '[balance] the app balance check answers the core\'s available balance and moves no money',
    run: async (MW, S) => {
      await S.note(['01711000001']);
      const available = (await MW.core.wallet('01711000001')).availableMinor;
      await S.balance('customer01', MW.pin);
      S.status(200);
      const b = S.last().body || {};
      S.eq('available balance, BDT', [b.available && b.available.valueMinor, b.available && b.available.currency], [available, 'BDT']);
      S.check('asOf and txnId are set', !!b.asOf && !!b.txnId, b);
      await S.noChange();
    },
  },
  {
    name: '[balance assertion] a wrong PIN on the app balance check is refused by mw-auth with MW-AUTH-040',
    run: async (MW, S) => {
      await S.balance('customer01', MW.WRONG_PIN);
      S.status(401);
      S.code('MW-AUTH-040');
      S.check('no balance in the refusal', !('available' in (S.last().body || {})), S.last().body);
    },
  },
  {
    name: '[balance] a core refusal of the balance check is 422 MW-CORE-WALLET_NOT_ACTIVE',
    run: async (MW, S) => {
      await S.reject('BalanceInquiry', 'WALLET_NOT_ACTIVE');
      await S.balance('customer01', MW.pin);
      S.status(422);
      S.code('MW-CORE-WALLET_NOT_ACTIVE');
    },
  },
  {
    name: '[balance history] a balance check is not in the wallet history',
    run: async (MW, S) => {
      await S.balance('customer01', MW.pin);
      S.status(200);
      const id = S.txnId();
      const h = await MW.app.history('customer01', 'limit=50');
      S.status(200, h);
      S.check('the balance check is not listed', !((h.body || {}).items || []).some(i => i.txnId === id || i.txnType === 'BALANCE'), String(h));
    },
  },
  {
    name: '[notify] the signed-in wallet reads its notification inbox and cannot mark another id read',
    run: async (MW, S) => {
      const page = await MW.http('GET', '/app/v1/notifications?limit=5&offset=0', { headers: await MW.app.headers('customer01') });
      S.record(page);
      S.status(200);
      S.eq('a page of at most 5', [Array.isArray(page.body.items), page.body.items.length <= 5, page.body.limit, page.body.offset],
        [true, true, 5, 0]);
      S.check('the inbox does not carry the raw MSISDN', !JSON.stringify(page.body).includes('01711000001'), String(page));
      S.record(await MW.http('POST', '/app/v1/notifications/no-such-message/read', { headers: await MW.app.headers('customer01') }));
      S.status(404);
      S.code('MW-VAL-003');
    },
  },
  {
    name: '[balance] the signed-in wallet\'s profile shows its name, status, KYC level and Bangla',
    run: async (MW, S) => {
      const r = await MW.http('GET', '/app/v1/profile', { headers: await MW.app.headers('customer01') });
      S.record(r);
      S.status(200);
      S.eq('Rahim Uddin, CUSTOMER, ACTIVE, FULL, bn',
        [r.body.name, r.body.type, r.body.status, r.body.kycLevel, r.body.language],
        ['Rahim Uddin', 'CUSTOMER', 'ACTIVE', 'FULL', 'bn']);
      S.check('the profile does not echo the MSISDN', !JSON.stringify(r.body).includes('01711000001'), String(r));
    },
  },
  {
    name: '[balance] the signed-in wallet\'s limits are what it may still pay',
    run: async (MW, S) => {
      const r = await MW.http('GET', '/app/v1/limits', { headers: await MW.app.headers('customer01') });
      S.record(r);
      S.status(200);
      const left = (r.body && r.body.remaining) || {};
      S.eq('per transaction maximum is 5,000 Tk', left.perTxn && left.perTxn.valueMinor, 500000);
      S.eq('BDT', left.daily && left.daily.currency, 'BDT');
      S.check('daily remaining is a number of poisha', typeof (left.daily && left.daily.valueMinor) === 'number' && left.daily.valueMinor >= 0, left);
      S.check('the limits do not carry the raw MSISDN', !JSON.stringify(r.body).includes('01711000001'), String(r));
    },
  },
];
