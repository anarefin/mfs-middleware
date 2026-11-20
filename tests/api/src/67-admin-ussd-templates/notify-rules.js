// Ticket 133c (FRS NOT-01). ops-e2e-maker01 proposes, ops-e2e-checker01 approves. Each run uses its
// own rule id and event type API_RULE_PAY, which no transaction has, so the rules this suite's
// flows read are untouched. mw-notify applying the rule is NotifyRuleChangeIT's (module test).
const rule = (channels) => ({
  eventType: 'API_RULE_PAY', outcome: 'SUCCESS', customerType: 'CUSTOMER', recipient: 'DEBIT_PARTY',
  channels, templateCode: 'TXN_P2P_SENT',
});

export default [
  {
    name: '[admin notify] an approved notify rule change is live with its new channel set',
    run: async (MW, S) => {
      const id = 'api-rule-' + Date.now().toString(36) + '-' + S.token;
      S.crFrom(await MW.admin.proposeNotifyRule('ops-e2e-maker01', id, { ...rule(['SMS']), reason: 'api rule ' + S.token }));
      await MW.admin.approve('ops-e2e-checker01', S.crId);
      await S.crIs('APPROVED');
      S.crFrom(await MW.admin.proposeNotifyRule('ops-e2e-maker01', id, { ...rule(['SMS', 'PUSH']), reason: 'push too ' + S.token }));
      await MW.admin.approve('ops-e2e-checker01', S.crId);
      await S.crIs('APPROVED');
      const r = await MW.admin.notifyRules('ops-e2e-checker01');
      S.eq('the rules answer (200)', r.status, 200, String(r));
      const live = (r.body.items || []).find(i => i.ruleId === id) || {};
      S.eq('the live version has the new channel set', [live.version, live.channels, live.enabled], [2, ['SMS', 'PUSH'], true]);
    },
  },
  {
    name: '[admin notify] a notify rule with a lowercase event type or a channel twice is refused',
    run: async (MW, S) => {
      const r = await MW.admin.proposeNotifyRule('ops-e2e-maker01', 'api-rule-refused',
        { ...rule(['SMS', 'SMS']), eventType: 'p2p', reason: 'refused ' + S.token });
      S.status(400, r);
      S.code('MW-VAL-040', r);
      const fields = (r.body.details || []).map(d => d.field);
      S.check('eventType and channels are named', fields.includes('eventType') && fields.includes('channels'), JSON.stringify(fields));
    },
  },
];
