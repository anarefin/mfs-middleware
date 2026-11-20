// Ticket 98. Amounts are unique so the duplicate window does not catch them. Each change is removed
// before the scenario ends (S.after), and ocs is switched back UP.
export default [
  {
    name: '[topup] a top-up with no partnerId is routed from the 017 prefix and completes',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE_INCOME']);
      await S.previewTopup('customer01', null, 'PREPAID', '01711000002', 9801, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.noHold('01711000001');
    },
  },
  {
    name: '[topup admin] an MNP override to an unknown operator refuses the number with MW-PTR-UNKNOWN',
    run: async (MW, S) => {
      const msisdn = '01711000077';
      S.after(async () => {
        const r = await MW.admin.proposeMnp('ops-e2e-maker01', msisdn, { action: 'DELETE', reason: 'api restore ' + S.token });
        if (r.body && r.body.id) await MW.admin.approve('ops-e2e-checker01', r.body.id);
      });
      S.crFrom(await MW.admin.proposeMnp('ops-e2e-maker01', msisdn, {
        reason: 'ported ' + S.token,
        value: { mno: 'nosuch', enabled: true },
      }));
      await MW.admin.approve('ops-e2e-checker01', S.crId);
      await S.crIs('APPROVED');
      const refused = await MW.poll(async () => {
        await S.previewTopup('customer01', null, 'PREPAID', msisdn, 9802, MW.pin);
        return S.last();
      }, x => x.status === 502 && x.body && x.body.code === 'MW-PTR-UNKNOWN', 30000, 1000);
      S.code('MW-PTR-UNKNOWN', refused);
    },
  },
  {
    name: '[topup mock] ocs switched DOWN fails the top-up at once and is switched back UP',
    run: async (MW, S) => {
      S.after(async () => { await MW.mock.switchTo('ocs', { state: 'UP' }); });
      await MW.mock.switchTo('ocs', { state: 'DOWN' });
      await S.previewTopup('customer01', 'gp', 'PREPAID', '01711000002', 9803, MW.pin);
      S.status(500);
      S.code('MW-SYS-PARTNER_UNAVAILABLE');
      await S.noHold('01711000001');
    },
  },
  {
    name: '[billpay admin] a maintenance message disables DESCO with the Bangla and English text',
    run: async (MW, S) => {
      S.after(async () => {
        const r = await MW.admin.proposeMaintenance('ops-e2e-maker01', 'desco-api', { action: 'DELETE', reason: 'api restore ' + S.token });
        if (r.body && r.body.id) await MW.admin.approve('ops-e2e-checker01', r.body.id);
      });
      S.crFrom(await MW.admin.proposeMaintenance('ops-e2e-maker01', 'desco-api', {
        reason: 'window ' + S.token,
        value: {
          billerCode: 'DESCO',
          messageEn: 'DESCO is under maintenance',
          messageBn: 'ডেসকো রক্ষণাবেক্ষণে আছে',
          enabled: true,
        },
      }));
      await MW.admin.approve('ops-e2e-checker01', S.crId);
      await S.crIs('APPROVED');
      const refused = await MW.poll(async () => {
        await S.previewBillPay('customer01', 'DESCO', 'ROUT98-' + S.token, 9804, MW.pin);
        return S.last();
      }, x => x.status === 500 && x.body && x.body.code === 'MW-SYS-PARTNER_DISABLED', 30000, 1000);
      S.code('MW-SYS-PARTNER_DISABLED', refused);
      S.eq('english maintenance text', refused.body.message, 'DESCO is under maintenance', String(refused));
      S.eq('bangla maintenance text', refused.body.messageBn, 'ডেসকো রক্ষণাবেক্ষণে আছে', String(refused));
    },
  },
];
