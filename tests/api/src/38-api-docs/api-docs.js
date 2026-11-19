// Ticket 101 (PLT-050..052): the generated API documents of the dev stack. The services run in
// profile dev, so springdoc serves /v3/api-docs and /swagger-ui there; the test-only APISIX routes
// test-docs and test-docs-<service> (MW_E2E_ROUTES=1) publish them under /docs/.
export const helpers = [
  function docServices() {
    return ['mw-app-gateway', 'mw-checkout', 'mw-portal-api', 'mw-ussd', 'mw-orchestrator', 'mw-audit-dw',
      'mw-admin-api', 'mw-auth', 'mw-core-mock', 'mw-simulators'];
  },
];

export default [
  {
    name: '[docs] /docs/ lists the Swagger UI and the OpenAPI document of every service',
    run: async (MW, S) => {
      const r = await MW.http('GET', '/docs/');
      S.status(200, r);
      S.check('/docs/ is an HTML page', /text\/html/.test(r.header('Content-Type') || ''));
      for (const svc of docServices()) {
        S.check('/docs/ links ' + svc + "'s Swagger UI", r.raw.includes('/docs/' + svc + '/swagger-ui/index.html'));
        S.check('/docs/ links ' + svc + "'s OpenAPI document", r.raw.includes('/docs/' + svc + '/v3/api-docs'));
      }
    },
  },
  {
    name: '[docs] every listed service serves its OpenAPI 3.1 document and its Swagger UI under /docs/<service>/',
    run: async (MW, S) => {
      for (const svc of docServices()) {
        const doc = await MW.http('GET', '/docs/' + svc + '/v3/api-docs');
        S.status(200, doc);
        S.check(svc + ' document is OpenAPI 3.1', !!doc.body && String(doc.body.openapi).startsWith('3.1'));
        S.check(svc + ' document has paths', !!doc.body && Object.keys(doc.body.paths || {}).length > 0);
        const ui = await MW.http('GET', '/docs/' + svc + '/swagger-ui/index.html');
        S.status(200, ui);
        S.check(svc + ' Swagger UI may load its own scripts', /default-src 'self'/.test(ui.header('Content-Security-Policy') || ''));
        const cfg = await MW.http('GET', '/docs/' + svc + '/v3/api-docs/swagger-config');
        S.status(200, cfg);
        S.eq(svc + ' Swagger UI reads the document relative to its prefix', cfg.body && cfg.body.url, '../v3/api-docs', String(cfg));
      }
    },
  },
  {
    name: '[docs] /docs/<service>/ reaches nothing of the service but its document and UI',
    run: async (MW, S) => {
      S.status(404, await MW.http('GET', '/docs/mw-audit-dw/test/v1/audit/events?limit=1'));
      S.status(404, await MW.http('GET', '/docs/mw-orchestrator/actuator/health'));
    },
  },
];
