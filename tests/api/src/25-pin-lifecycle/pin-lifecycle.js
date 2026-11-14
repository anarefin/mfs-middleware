// Ticket 76: mw-auth's PIN lifecycle (ticket 70, spec 18.7, D20) black-box through the edge: the
// weak-PIN policy, the PIN change, the self-service reset with the core's factor check, the care
// reset with maker-checker in mw-admin-api, the lockout and the ops unlock. Dev lockout timers
// (deploy/dev.env.defaults): MW_AUTH_LOCK_TRIES=3, level 1 = MW_AUTH_LOCK_LEVEL1 (1 min). Every
// scenario takes a fresh wallet (MW.auth.fresh); folder 90 runs the full escalation to level 3.
export default [
  {
    name: '[auth pin] a weak PIN is refused naming the rule; a good one is set',
    run: async (MW, S) => {
      const w = await MW.auth.fresh(S, null);
      const t = w.otpLogin.accessToken;
      for (const [pin, rule] of [['11111', 'REPEATED_DIGIT'], ['34567', 'SEQUENCE'], ['09876', 'SEQUENCE'], [w.msisdn.slice(6), 'MSISDN_DIGITS']]) {
        const r = S.record(await MW.auth.setPin(t, pin));
        S.status(400);
        S.code('MW-AUTH-044');
        S.check('the refusal names the rule ' + rule, JSON.stringify(r.body).includes(rule), r.body);
      }
      S.record(await MW.auth.setPin(t, '48207'));
      S.status(200);
    },
  },
  {
    name: '[auth pin] a PIN change needs the current PIN, refuses the same PIN again and replaces the session',
    run: async (MW, S) => {
      const w = await MW.auth.fresh(S, '48207');
      const t = w.tokens;
      S.record(await MW.auth.changePin(t.accessToken, MW.WRONG_PIN, '61938'));
      S.status(401);
      S.code('MW-AUTH-040');
      const same = S.record(await MW.auth.changePin(t.accessToken, '48207', '48207'));
      S.status(400);
      S.code('MW-AUTH-044');
      S.check('the rule is LAST_PIN', JSON.stringify(same.body).includes('LAST_PIN'), same.body);
      const notice = 'Your Bits PIN was changed. If this was not you, call Bits care at once.';
      const before = (await MW.sim.sms(w.msisdn)).filter(m => m.text === notice).length;
      const c = S.record(await MW.auth.changePin(t.accessToken, '48207', '61938'));
      S.status(200);
      S.check('new tokens with amr pin + device', MW.claims(c.body.accessToken).amr.includes('pin') && c.body.refreshToken !== t.refreshToken);
      S.record(await MW.auth.refresh(w.d, t.refreshToken));
      S.status(401);
      S.check('the old session is revoked', ['MW-AUTH-048', 'MW-AUTH-001'].includes(S.last().body.code), String(S.last()));
      S.record(await MW.auth.login(w.d, w.msisdn, { pin: '48207' }));
      S.status(401);
      S.code('MW-AUTH-040');
      S.record(await MW.auth.login(w.d, w.msisdn, { pin: '61938' }));
      S.status(200);
      const got = await MW.poll(async () => (await MW.sim.sms(w.msisdn)).filter(m => m.text === notice).length, n => n > before, 30000, 700);
      S.eq('the wallet got the PIN changed SMS', got, before + 1);
    },
  },
  {
    // spec 18.7: the reset OTP on the bound device, the NID and birth date checked by the core
    name: '[auth pin] a self-service reset is verified by the core, sets a new PIN and ends every session',
    run: async (MW, S) => {
      const w = await MW.auth.fresh(S, '48207');
      const o = await MW.auth.ask(w.d, w.msisdn, 'RESET');
      S.record(o.r);
      S.status(202);
      const start = S.record(await MW.auth.startReset(w.d, w.msisdn, o.code, { nidLast4: '4821', dateOfBirth: '1990-05-17' }));
      S.status(202);
      S.eq('the reset is SELF, waiting for the core', [start.body.kind, start.body.status], ['SELF', 'PENDING_KYC']);
      const id = start.body.resetId;
      const other = await MW.auth.device();
      S.record(await MW.auth.reset(other, id));
      S.status(404);
      S.code('MW-VAL-003');
      const v = await MW.poll(() => MW.auth.reset(w.d, id), r => r.body && r.body.status !== 'PENDING_KYC', 30000, 700);
      S.eq('the core verified the factors', [v.status, v.body && v.body.status], [200, 'VERIFIED'], String(v));
      S.record(await MW.auth.resetPin(w.d, id, '22222'));
      S.status(400);
      S.code('MW-AUTH-044');
      const done = S.record(await MW.auth.resetPin(w.d, id, '73052'));
      S.status(200);
      S.eq('the reset is DONE', done.body.status, 'DONE');
      S.record(await MW.auth.resetPin(w.d, id, '73052'));
      S.status(409);
      S.code('MW-VAL-031');
      S.record(await MW.auth.refresh(w.d, w.tokens.refreshToken));
      S.status(401);
      S.record(await MW.auth.login(w.d, w.msisdn, { pin: '48207' }));
      S.status(401);
      S.code('MW-AUTH-040');
      S.record(await MW.auth.login(w.d, w.msisdn, { pin: '73052' }));
      S.status(200);
    },
  },
  {
    name: '[auth pin admin] a care PIN reset needs a second operator; the customer then sets a new PIN on a new phone',
    run: async (MW, S) => {
      const w = await MW.auth.fresh(S, '48207');
      const phone = await MW.auth.device();
      // a lost phone: the new device is not bound, so no reset OTP without an approved care reset
      S.record(await MW.auth.otp(phone, w.msisdn, 'RESET'));
      S.status(403);
      S.code('MW-AUTH-002');
      S.crFrom(await MW.admin.askWallet('ops-e2e-maker01', w.msisdn, 'PIN_RESET', 'customer identified at the branch, phone lost'));
      await S.crIs('PENDING');
      S.adminAnswers(await MW.admin.approve('ops-e2e-maker01', S.crId), 403, 'MW-AUTH-002');
      S.record(await MW.auth.otp(phone, w.msisdn, 'RESET'));
      S.status(403);
      await MW.admin.approve('ops-e2e-checker01', S.crId);
      await S.crIs('APPROVED');
      const approved = 'Your Bits PIN reset was approved. Open the app, ask for a reset code and set a new PIN within 72 hours.';
      const sms = await MW.poll(async () => (await MW.sim.sms(w.msisdn)).filter(m => m.text === approved), l => l.length > 0, 30000, 1000);
      S.eq('the wallet got the care reset SMS', sms.length, 1, sms);
      // mw-auth opened the CARE reset from auth.wallet-lock.v1: any device may now ask the reset OTP
      const o = await MW.auth.ask(phone, w.msisdn, 'RESET');
      S.record(o.r);
      S.status(202);
      const take = S.record(await MW.auth.startReset(phone, w.msisdn, o.code, null));
      S.status(202);
      S.eq('the care reset is VERIFIED for this phone', [take.body.kind, take.body.status], ['CARE', 'VERIFIED']);
      S.record(await MW.auth.resetPin(phone, take.body.resetId, '73052'));
      S.status(200);
      // then the phone is bound with the new PIN and logs in; the old phone's session is gone
      const b = await MW.auth.ask(phone, w.msisdn, 'BIND');
      S.record(await MW.auth.bind(phone, w.msisdn, b.code, '73052'));
      S.status(201);
      S.record(await MW.auth.login(phone, w.msisdn, { pin: '73052' }));
      S.status(200);
      S.record(await MW.auth.refresh(w.d, w.tokens.refreshToken));
      S.status(401);
    },
  },
  {
    // MW_AUTH_LOCK_TRIES=3: the third wrong PIN locks at level 1; the lock is checked before the PIN
    name: '[auth pin lock admin] three wrong PINs lock the PIN, the right one is not even checked, an approved ops unlock opens it',
    run: async (MW, S) => {
      const w = await MW.auth.fresh(S, '48207');
      for (const left of ['2', '1']) {
        const r = S.record(await MW.auth.login(w.d, w.msisdn, { pin: MW.WRONG_PIN }));
        S.status(401);
        S.code('MW-AUTH-040');
        S.eq('tries left ' + left, (r.body.details || []).filter(x => x.field === 'triesLeft').map(x => x.issue), [left]);
      }
      const locked = S.record(await MW.auth.login(w.d, w.msisdn, { pin: MW.WRONG_PIN }));
      S.status(423);
      S.code('MW-AUTH-041');
      const after = Number(((locked.body.details || []).find(x => x.field === 'retryAfter') || {}).issue);
      S.check('retryAfter within level 1 (1 min with the dev timers)', after > 0 && after <= 60, locked.body);
      S.record(await MW.auth.login(w.d, w.msisdn, { pin: '48207' }));
      S.status(423);
      S.code('MW-AUTH-041');
      S.crFrom(await MW.admin.askWallet('ops-e2e-maker01', w.msisdn, 'UNLOCK', 'customer identified at the branch'));
      await MW.admin.approve('ops-e2e-checker01', S.crId);
      await S.crIs('APPROVED');
      const open = await MW.poll(() => MW.auth.login(w.d, w.msisdn, { pin: '48207' }), r => r.status !== 423, 30000, 1000);
      S.eq('the right PIN logs in within 30 s of the approval', open.status, 200, String(open));
    },
  },
];
