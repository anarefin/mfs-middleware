// Port of wallet-unlock.feature, on mw-auth's PIN lockout since ticket 72 (the core holds no PIN any
// more). customer02 (01711000002) is the payer so customer01 is never locked; other scenarios only
// credit 01711000002, which a PIN-locked wallet still accepts. Dev lockout: MW_AUTH_LOCK_TRIES=3,
// level 1 = MW_AUTH_LOCK_LEVEL1 (1 min), so the unlock is what lets the next P2P through at once.
export default [
  {
    name: '[admin wallet-unlock] three wrong PINs lock the PIN at mw-auth, an approved unlock lets the next correct-PIN P2P succeed',
    run: async (MW, S) => {
      for (let i = 1; i <= 2; i++) {
        await S.p2p('customer02', '01711000001', 1000, MW.WRONG_PIN);
        S.status(401);
        S.code('MW-AUTH-040');
      }
      await S.p2p('customer02', '01711000001', 1000, MW.WRONG_PIN);
      S.status(423);
      S.code('MW-AUTH-041');
      await S.p2p('customer02', '01711000001', 1000, MW.pin);
      S.status(423);
      S.code('MW-AUTH-041');
      S.crFrom(await MW.admin.askWallet('ops-e2e-maker01', '01711000002', 'UNLOCK', 'customer identified at the branch'));
      await S.crIs('PENDING');
      S.adminAnswers(await MW.admin.approve('ops-e2e-maker01', S.crId), 403, 'MW-AUTH-002');
      await S.p2p('customer02', '01711000001', 1000, MW.pin);
      S.status(423);
      await MW.admin.approve('ops-e2e-checker01', S.crId);
      await S.crIs('APPROVED');
      // the UNLOCK reaches mw-auth over auth.wallet-lock.v1 (asynchronous): poll with the right PIN
      const r = await MW.poll(async () => {
        S.responses = [];
        await S.p2p('customer02', '01711000001', 1000, MW.pin);
        return S.last().status;
      }, st => st !== 423, 30000, 1000);
      S.eq('the right PIN goes through within 30 seconds of the approval', r, 200);
      S.txnStatus('COMPLETED');
    },
  },
];
