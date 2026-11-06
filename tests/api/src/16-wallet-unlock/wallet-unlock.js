// Port of wallet-unlock.feature. customer02 (01711000002) is the payer so customer01 is never
// locked; other scenarios only credit 01711000002, which a locked wallet still accepts.
export default [
  {
    name: '[admin wallet-unlock] five wrong PINs lock the wallet, an approved unlock lets the next correct-PIN P2P succeed',
    run: async (MW, S) => {
      S.eq('the core shows wallet 01711000002 as ACTIVE', (await MW.core.wallet('01711000002')).status, 'ACTIVE');
      for (let i = 1; i <= 5; i++) {
        await S.p2p('customer02', '01711000001', 1000, '9999');
        S.code('MW-CORE-INVALID_PIN');
      }
      S.eq('the core shows wallet 01711000002 as LOCKED', (await MW.core.wallet('01711000002')).status, 'LOCKED');
      await S.p2p('customer02', '01711000001', 1000, '1234');
      S.status(422);
      S.code('MW-CORE-ACCOUNT_LOCKED');
      S.crFrom(await MW.admin.askWallet('ops-e2e-maker01', '01711000002', 'UNLOCK', 'customer identified at the branch'));
      await S.crIs('PENDING');
      S.adminAnswers(await MW.admin.approve('ops-e2e-maker01', S.crId), 403, 'MW-AUTH-002');
      S.eq('still LOCKED', (await MW.core.wallet('01711000002')).status, 'LOCKED');
      await MW.admin.approve('ops-e2e-checker01', S.crId);
      await S.crIs('APPROVED');
      const st = await MW.poll(async () => (await MW.core.wallet('01711000002')).status, s => s === 'ACTIVE', 30000, 500);
      S.eq('the core shows wallet 01711000002 as ACTIVE within 30 seconds', st, 'ACTIVE');
      await S.p2p('customer02', '01711000001', 1000, '1234');
      S.status(200);
      S.txnStatus('COMPLETED');
    },
  },
];
