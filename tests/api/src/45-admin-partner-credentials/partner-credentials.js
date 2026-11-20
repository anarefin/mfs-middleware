// Ticket 131a. ops-e2e-maker01 holds integration-maker, ops-e2e-checker01 integration-checker (realm ops).
// Credentials go to the field api45-* of secret/mw-adapter-telco/ocs, which no route names: the partner
// keeps working; a rerun rotates them (the next version). The error map is put back afterwards.
export default [
  {
    name: '[admin partner-config credentials] an uploaded partner credential is approved by another checker into Vault and only its metadata is ever shown',
    run: async (MW, S) => {
      const secret = 'api45-fake-key-' + Date.now() + '-abcdefghij';
      const asked = await MW.admin.uploadCredential('ops-e2e-maker01', 'ocs', 'api45-key',
        { action: 'UPLOAD', reason: 'api: rotate OCS test key', type: 'API_KEY', secret });
      S.eq('upload change request created (201)', asked.status, 201, String(asked));
      S.eq('it is a partner_credential request', asked.body && asked.body.resourceType, 'partner_credential');
      S.check('the request never carries the secret', !JSON.stringify(asked.body).includes(secret), 'secret echoed');
      S.eq('it targets the partner\'s Vault secret', asked.body.payload.vaultPath, 'mw-adapter-telco/ocs');
      const own = await MW.admin.approve('ops-e2e-maker01', asked.body.id);
      S.eq('the maker cannot approve it (403)', own.status, 403, String(own));
      const approved = await MW.admin.approve('ops-e2e-checker01', asked.body.id);
      S.eq('the checker approves it', [approved.status, approved.body && approved.body.status], [200, 'APPROVED'], String(approved));
      S.check('the approval never carries the secret', !JSON.stringify(approved.body).includes(secret), 'secret echoed');
      const list = await MW.admin.credentials('ops-e2e-checker01', 'ocs');
      S.eq('credentials listed (200)', list.status, 200, String(list));
      const item = (list.body.items || []).find(c => c.credentialId === 'ocs/api45-key');
      S.check('the credential is listed with its metadata', !!item && item.kid === 'api45-key-v' + item.version
        && item.adapter === 'mw-adapter-telco' && item.approvedBy === 'ops-e2e-checker01', JSON.stringify(item));
      S.check('the list never carries the secret', !JSON.stringify(list.body).includes(secret), 'secret listed');
    },
  },
  {
    name: '[admin credentials] the expiry dashboard lists a credential expiring within 30 days, soonest first',
    run: async (MW, S) => {
      const notAfter = new Date(Date.now() + 10 * 86400000).toISOString();
      const asked = await MW.admin.uploadCredential('ops-e2e-maker01', 'ocs', 'api45-expiring',
        { action: 'UPLOAD', reason: 'api: short-lived test key', type: 'HMAC_SECRET', secret: 'api45-fake-hmac-' + Date.now() + '-abcdef', notAfter });
      S.eq('upload change request created (201)', asked.status, 201, String(asked));
      const approved = await MW.admin.approve('ops-e2e-checker01', asked.body.id);
      S.eq('the checker approves it', approved.status, 200, String(approved));
      const soon = await MW.admin.expiring('ops-e2e-checker01', 30);
      S.eq('expiring credentials listed (200)', soon.status, 200, String(soon));
      const item = (soon.body.items || []).find(c => c.credentialId === 'ocs/api45-expiring');
      S.check('it shows with 9 or 10 days left', !!item && item.daysLeft >= 9 && item.daysLeft <= 10, JSON.stringify(item));
      const later = await MW.admin.expiring('ops-e2e-checker01', 5);
      S.check('a 5-day window leaves it out', !(later.body.items || []).some(c => c.credentialId === 'ocs/api45-expiring'),
        JSON.stringify(later.body));
    },
  },
  {
    name: '[admin partner-config] an approved response-code map is published as the partner\'s next version',
    run: async (MW, S) => {
      const before = await MW.admin.partner('ops-e2e-checker01', 'ocs');
      S.eq('the partner is read (200)', before.status, 200, String(before));
      const held = before.body.config.errorMap || {};
      S.after(async () => {
        const back = await MW.admin.askErrorMap('ops-e2e-maker01', 'ocs', { action: 'SET_ERROR_MAP', reason: 'api: restore', errorMap: held });
        if (back.status === 201) await MW.admin.approve('ops-e2e-checker01', back.body.id); // 409: nothing to change
      });
      const next = Object.assign({}, held, { API45: { suffix: 'API45_TEST', retryable: true } });
      const asked = await MW.admin.askErrorMap('ops-e2e-maker01', 'ocs', { action: 'SET_ERROR_MAP', reason: 'api: map a code', errorMap: next });
      S.eq('error-map change request created (201)', asked.status, 201, String(asked));
      S.eq('it is a partner_error_map request', asked.body && asked.body.resourceType, 'partner_error_map');
      const approved = await MW.admin.approve('ops-e2e-checker01', asked.body.id);
      S.eq('the checker approves it', [approved.status, approved.body && approved.body.status], [200, 'APPROVED'], String(approved));
      S.check('the published version goes up', approved.body.payload.version > before.body.version, approved.body.payload.version);
      const after = await MW.admin.partner('ops-e2e-checker01', 'ocs');
      S.eq('the partner carries the new code', after.body.config.errorMap.API45 && after.body.config.errorMap.API45.suffix, 'API45_TEST');
    },
  },
];
