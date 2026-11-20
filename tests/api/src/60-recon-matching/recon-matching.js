// Ticket 126 (FRS REC-01, REC-04, EXT-06): one injected trust-bank line is missing in Upay.
export default [
  {
    name: "[mock] an injected trust-bank line is missing in Upay",
    run: async (MW, S) => {
      S.after(() => MW.mock.reset('trustbank'));
      await MW.mock.reset('trustbank');
      await MW.mock.scenario('trustbank', {
        operation: 'mt940', behaviour: 'INJECTED_MISMATCH', code: 'MISSING_IN_UPAY', count: 1, nextN: 1,
      });
      const written = await MW.http('POST', '/test/mock-control/trustbank/run', {
        apikey: true, json: { operation: 'mt940' },
      });
      S.status(200, written);
      const pulled = await MW.http('POST', '/test/recon/v1/pull', { apikey: true });
      S.status(200, pulled);
      const runs = Array.isArray(pulled.body) ? pulled.body : [];
      const classified = runs.some((r) => (r.missingInUpay || 0) >= 1);
      const already = runs.some((r) => r.skipped && String(r.fileName || '').indexOf('MT940_') === 0);
      S.check('the injected line is classified, or that file was already registered', classified || already, pulled.body);
    },
  },
];
