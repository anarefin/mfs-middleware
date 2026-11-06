// 00 Health & seed check: the stack is reachable through APISIX, the test-only routes are applied,
// and the mw-seeder baseline (ticket 57) is there. Every later folder relies on these.
export default [
  {
    name: '[health] the edge and every test-only route answer',
    run: async (MW, S) => {
      for (const k of ['e2eApiKey', 'kcCustomersPassword', 'kcPortalsPassword', 'kcOpsPassword', 'kcOpsTotpSecret', 'billaggSecret', 'checkoutSecret']) {
        S.check('environment has ' + k + ' (run.sh reads it from deploy/.env)', !!MW.v(k));
      }
      S.status(200, await MW.http('GET', '/healthz'));
      S.status(200, await MW.http('GET', '/test/core-mock/v1/pin-public-key', { apikey: true }));
      S.status(200, await MW.http('GET', '/test/sim/smsc/messages?msisdn=01711000001', { apikey: true }));
      S.status(200, await MW.http('GET', '/test/audit-dw/v1/audit/events?limit=1', { apikey: true }));
      S.status(404, await MW.http('GET', '/test/orchestrator/v1/txn-requests/api-unknown-key', { apikey: true }));
      S.status(401, await MW.http('GET', '/test/core-mock/v1/pin-public-key'));
    },
  },
  {
    // A freshly started stack answers its first money calls slowly (connections, JIT); one P2P per
    // channel user warms the path, so the first real scenario is not answered 202 by a cold stack.
    name: '[health] the money path is warm: a P2P per app user completes',
    run: async (MW, S) => {
      for (const [user, type, credit] of [['customer01', 'P2P', '01711000002'], ['agent01', 'CASH_IN', '01711000001']]) {
        S.responses = [];
        await S.txn(user, type, credit, 100, '1234');
        await S.appShows('COMPLETED', 60);
      }
    },
  },
  {
    name: '[health seed] the seeded personas sign in and the seeded wallets exist',
    run: async (MW, S) => {
      S.check('customer seed-customer01 has a token', !!(await MW.token('customers', 'seed-customer01')));
      S.check('portal seed-org-maker has a token', !!(await MW.token('portals', 'seed-org-maker')));
      S.check('ops seed-ops-viewer has a token (password + TOTP)', !!(await MW.token('ops', 'seed-ops-viewer')));
      for (const w of ['01712000001', '01712000002', '01812000001', '01912000001', '01312000001']) {
        const r = await MW.http('GET', '/test/core-mock/v1/wallets/' + w, { apikey: true });
        S.status(200, r);
      }
      const partner = await MW.admin.partner('seed-ops-viewer', 'ocs');
      S.eq('partner ocs at its seed baseline (enabled, the manifest time limit)', [partner.status, (partner.body.config || {}).enabled, (partner.body.config || {}).timeoutMs],
        [200, true, MW.ocsLimit()], String(partner));
    },
  },
];
