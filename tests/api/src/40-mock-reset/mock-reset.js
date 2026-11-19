// Ticket 103 (FRS MCT-07, MCK-08): the global mock reset. mw-simulators' POST /mock-control/reset resets
// its parties and calls mw-core-mock's own reset (ledger back to seed/v1, scenarios, faults, journal gone).
// Reached through the test-only route /test/mock-control/* (MW_E2E_ROUTES=1, key-auth).
export const helpers = [
  async function mockReset(MW, body) {
    return MW.http('POST', '/test/mock-control/reset', body ? { apikey: true, json: body } : { apikey: true });
  },
];

export default [
  {
    name: '[mock] one call resets every mock party and brings the core ledger back to its seed',
    run: async (MW, S) => {
      const scenario = await MW.http('POST', '/test/mock-control/ocs/scenarios',
        { apikey: true, json: { operation: 'topup', behaviour: 'TIMEOUT' } });
      S.status(201, scenario);

      const r = await mockReset(MW, { seedVersion: 'v1' });
      S.status(200, r);
      const parties = (r.body && r.body.parties) || [];
      S.check('every party reports RESET', parties.length > 1 && parties.every(p => p.status === 'RESET'), r.body);
      const core = parties.find(p => p.party === 'core');
      S.check('the core is one of them, reloaded from seed v1', !!core && core.seedVersion === 'v1', r.body);

      const left = await MW.http('GET', '/test/mock-control/ocs/scenarios', { apikey: true });
      S.status(200, left);
      S.eq('no ocs scenario is left', (left.body || []).length, 0, String(left));
      // 01711000899: a seed customer no other scenario of the suite pays from (seed/v1/wallets.csv: 10,000 Tk)
      const w = await MW.core.wallet('01711000899');
      S.eq('a seed wallet is at its seed balance', w.availableMinor, 1000000, w);
      S.eq('nothing is held', w.heldMinor, 0, w);
    },
  },
  {
    name: '[mock] a fault set through the old core fault API is a core scenario the reset clears',
    run: async (MW, S) => {
      await MW.core.fault({ mode: 'DROP_REPLY', commandType: 'PostTransfer', times: 1 });
      const before = await MW.http('GET', '/test/core-mock/v1/faults', { apikey: true });
      S.eq('the fault is listed', (before.body || []).length, 1, String(before));

      S.status(200, await mockReset(MW));

      const after = await MW.http('GET', '/test/core-mock/v1/faults', { apikey: true });
      S.status(200, after);
      S.eq('the reset cleared it', (after.body || []).length, 0, String(after));
    },
  },
  {
    name: '[mock] an unknown core seed version is reported FAILED and changes nothing else',
    run: async (MW, S) => {
      const r = await mockReset(MW, { seedVersion: 'v404' });
      S.status(200, r);
      const core = ((r.body && r.body.parties) || []).find(p => p.party === 'core');
      S.check('the core reports FAILED with the reason', !!core && core.status === 'FAILED' && /400/.test(core.detail || ''), r.body);
      const w = await MW.core.wallet('01711000899');
      S.eq('the seed wallet is untouched', w.availableMinor, 1000000, w);
    },
  },
];
