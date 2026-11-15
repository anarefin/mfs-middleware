// Ticket 76: the two security scenarios whose time is a deliberate wait. With the dev timers of
// deploy/dev.env.defaults (MW_AUTH_LOCK_TRIES=3, MW_AUTH_LOCK_LEVEL1=1m, MW_AUTH_LOCK_LEVEL2=3m) the
// escalation takes about 4.5 min; the assertion life is 60 s (mw.auth.assertion, not a knob).
export default [
  {
    name: '[auth pin lock] the lockout escalates: level 1, level 2 after it ran out, then a permanent lock that only a reset ends',
    run: async (MW, S) => {
      const w = await MW.auth.fresh(S, '48207');
      const strikes = async () => {
        let r;
        for (let i = 0; i < 3; i++) r = await MW.auth.login(w.d, w.msisdn, { pin: MW.WRONG_PIN });
        return S.record(r);
      };
      const retryAfter = r => Number(((r.body.details || []).find(x => x.field === 'retryAfter') || {}).issue);
      let r = await strikes();
      S.status(423);
      S.code('MW-AUTH-041');
      S.check('level 1: at most MW_AUTH_LOCK_LEVEL1 (60 s)', retryAfter(r) > 0 && retryAfter(r) <= 60, r.body);
      await MW.sleep((retryAfter(r) + 2) * 1000);
      r = await strikes();
      S.status(423);
      S.code('MW-AUTH-041');
      S.check('level 2: longer than level 1, at most MW_AUTH_LOCK_LEVEL2 (180 s)', retryAfter(r) > 60 && retryAfter(r) <= 180, r.body);
      await MW.sleep((retryAfter(r) + 2) * 1000);
      r = await strikes();
      S.status(423);
      S.code('MW-AUTH-042');
      S.record(await MW.auth.login(w.d, w.msisdn, { pin: '48207' }));
      S.status(423);
      S.code('MW-AUTH-042');
      // a PIN lock does not stop the self-service reset, which clears it
      const o = await MW.auth.ask(w.d, w.msisdn, 'RESET');
      const start = S.record(await MW.auth.startReset(w.d, w.msisdn, o.code, { nidLast4: '4821', dateOfBirth: '1990-05-17' }));
      S.status(202);
      const v = await MW.poll(() => MW.auth.reset(w.d, start.body.resetId), x => x.body && x.body.status !== 'PENDING_KYC', 30000, 700);
      S.eq('verified', v.body && v.body.status, 'VERIFIED', String(v));
      S.record(await MW.auth.resetPin(w.d, start.body.resetId, '73052'));
      S.status(200);
      S.record(await MW.auth.login(w.d, w.msisdn, { pin: '73052' }));
      S.status(200);
    },
  },
  {
    name: '[assertion p2p] after the assertion life (60 s) a resend with the same key checks the PIN again; the right PIN gets the same txnId',
    run: async (MW, S) => {
      // pays 01712000001, not customer02: the 10,000-row payout of this folder credits 01711000001..01711001000, and
      // the folder's requests may run in parallel
      await S.note(['01711000001', '01712000001', 'FEE-POOL']);
      await S.p2p('customer01', '01712000001', 1000, MW.pin);
      S.status(200);
      const idem = S.idem;
      await MW.sleep(62000);
      await S.submit('customer01', await MW.app.request('P2P', '01712000001', 1000, MW.WRONG_PIN), idem);
      S.status(401);
      S.code('MW-AUTH-040');
      S.responses = S.responses.filter(x => x.status !== 401);
      await S.submit('customer01', await MW.app.request('P2P', '01712000001', 1000, MW.pin), idem);
      S.status(200);
      S.sameTxnId();
      await S.changedBy({ '01711000001': -1500, '01712000001': 1000, 'FEE-POOL': 500 });
    },
  },
];
