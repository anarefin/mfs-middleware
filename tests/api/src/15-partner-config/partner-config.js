// Port of partner-config.feature. ops-e2e-maker01 asks, ops-e2e-checker01 approves; mw-admin-api
// publishes on mw.config.changed.v1 and mw-adapter-telco swaps the config in while it runs.
// Cucumber paused 5 s for "the partner change has reached the adapters"; here the top-up itself is
// retried until it shows the new config (no adapter endpoint shows the applied version).
// Every scenario puts ocs back to its seed (enabled, the mw-seeder baseline limit) afterwards.
export default [
  {
    name: '[admin partner-config topup] a disabled telco partner refuses top-ups, and works again once enabled',
    run: async (MW, S) => {
      restoreAfter(MW, S, 'ocs');
      await change(MW, S, 'ocs', { action: 'DISABLE', reason: 'api: partner incident' });
      // retried until the adapter took the change: a top-up that still went through is not checked
      await MW.poll(async () => {
        S.responses = [];
        await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
        return S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2000, '1234');
      }, x => !!x.body && x.body.code === 'MW-SYS-PARTNER_DISABLED', 30000, 1000);
      S.code('MW-SYS-PARTNER_DISABLED');
      await S.appShows('FAILED');
      await S.noChange();
      await S.noHold('01711000001');
      await change(MW, S, 'ocs', { action: 'ENABLE', reason: 'api: partner back' });
      const ok = await MW.poll(async () => { S.responses = []; return S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2000, '1234'); },
        x => x.status === 200, 30000, 1000);
      S.status(200, ok);
      S.txnStatus('COMPLETED');
    },
  },
  {
    name: '[admin partner-config topup tsq] a lowered time limit applies to the next call',
    run: async (MW, S) => {
      restoreAfter(MW, S, 'ocs');
      // Cucumber: OCS answers after 6 s, limit lowered from 20 s to 3 s; with TIMERS=fast (4 s seed
      // limit) 3 s and 1.5 s
      const latency = Math.min(6000, MW.ocsLimit() - 1000);
      await S.force('ocs', { latencyMs: latency, errorRate: 0.0, outage: false, callbackMode: 'NONE', callbackDelayMs: 0 });
      await change(MW, S, 'ocs', { action: 'SET_TIMEOUTS', reason: 'api: fail faster', timeouts: { timeoutMs: Math.floor(latency / 2) } });
      // until the adapter applies the lower limit the slow answer still completes synchronously (200)
      const r = await MW.poll(async () => { S.responses = []; return S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2000, '1234'); },
        x => x.status === 202, 40000, 500);
      S.status(202, r);
      await MW.sim.reset('ocs');
      await S.appShows('COMPLETED', 120);
      await S.auditStates('RECEIVED, VALIDATING, HOLDING, PARTNER_PENDING, UNKNOWN, COMMITTING, COMPLETED');
    },
  },
];

async function change(MW, S, partnerId, body) {
  const asked = await MW.admin.askPartner('ops-e2e-maker01', partnerId, body);
  S.eq('partner change request created (201)', asked.status, 201, String(asked));
  const own = await MW.admin.approve('ops-e2e-maker01', asked.body.id);
  S.eq('the maker cannot approve it (403)', own.status, 403, String(own));
  const approved = await MW.admin.approve('ops-e2e-checker01', asked.body.id);
  S.eq('the checker approves it', [approved.status, approved.body && approved.body.status], [200, 'APPROVED'], String(approved));
  const version = approved.body.payload.version;
  S.check('the published version goes up', version > (S.partnerVersion || 0), version);
  S.partnerVersion = version;
  const view = await MW.admin.partner('ops-e2e-checker01', partnerId);
  S.eq('the partner shows the published version', view.body && view.body.version, version);
}
function restoreAfter(MW, S, partnerId) {
  S.after(async () => {
    for (const b of [{ action: 'ENABLE', reason: 'api: restore' }, { action: 'SET_TIMEOUTS', reason: 'api: restore', timeouts: { timeoutMs: MW.ocsLimit() } }]) {
      const asked = await MW.admin.askPartner('ops-e2e-maker01', partnerId, b);
      if (asked.status === 201) await MW.admin.approve('ops-e2e-checker01', asked.body.id); // 409: nothing to change
    }
  });
}
export const helpers = [change, restoreAfter];
