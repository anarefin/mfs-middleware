// Ticket 133b (FRS ADM-06, CH-USSD-06). ops-e2e-maker01 holds ops-maker. The preview stores and
// publishes nothing, so a rerun sees the same answers. Fake texts only.
export default [
  {
    name: '[admin ussd] a draft menu preview pages a long Bangla screen at the UCS-2 limit with 00 Back and names the short code',
    run: async (MW, S) => {
      const t = (bn, en) => ({ bn, en });
      const labels = ['সেন্ড মানি', 'ক্যাশ আউট', 'মোবাইল রিচার্জ', 'পেমেন্ট', 'পে বিল', 'অ্যাড মানি', 'আমার অ্যাকাউন্ট'];
      const menu = {
        start: 'main',
        messages: {
          invalid: t('ভুল ইনপুট।', 'Invalid input.'), tooManyAttempts: t('অনেকবার ভুল', 'Too many tries.'),
          expired: t('শেষ', 'Expired'), processing: t('প্রক্রিয়াধীন', 'Processing'),
          unavailable: t('বন্ধ', 'Unavailable'), more: t('0. পরবর্তী', '0. Next'),
        },
        nodes: {
          main: { type: 'MENU', text: t('আপনার সেবা বেছে নিন', 'Choose a service'),
            options: labels.map((l, i) => ({ key: String(i + 1), label: t(l, 'Service ' + (i + 1)), next: 'done' })) },
          done: { type: 'END', text: t('ধন্যবাদ', 'Thank you') },
        },
      };
      const r = await MW.admin.previewMenu('ops-e2e-maker01', { menu, menuId: 'main', mno: 'robi', language: 'bn', inputs: ['0', '00'] });
      S.eq('the preview answers (200)', r.status, 200, String(r));
      S.eq('robi dials *269#, which opens main', [r.body.shortCode, r.body.rootMenuId], ['*269#', 'main']);
      const [dial, next, back] = r.body.screens;
      S.check('the dial screen is paged', dial.pages > 1, JSON.stringify(dial));
      S.check('every page fits 70 UCS-2 characters', r.body.screens.every(s => s.screen.length <= 70), JSON.stringify(r.body.screens));
      S.check('the first page ends with 0. Next and 00. Back', dial.screen.endsWith('\n0. পরবর্তী\n00. পেছনে'), dial.screen);
      S.eq('0 shows the next page, 00 the previous one', [next.page, back.page], [1, 0]);
    },
  },
  {
    name: '[admin ussd] a draft menu with a dangling node and a stored PIN is refused with the validator problems',
    run: async (MW, S) => {
      const t = (bn, en) => ({ bn, en });
      const messages = {};
      for (const m of ['invalid', 'tooManyAttempts', 'expired', 'processing', 'unavailable', 'more']) messages[m] = t('বার্তা', 'Message');
      const menu = {
        start: 'main',
        messages,
        nodes: {
          main: { type: 'MENU', text: t('বিটস', 'Bits'), options: [{ key: '1', label: t('পাঠান', 'Send'), next: 'nowhere' }, { key: '2', label: t('পিন', 'PIN'), next: 'pin' }] },
          pin: { type: 'INPUT', text: t('পিন দিন', 'Enter PIN'), validator: { type: 'pin' }, store: 'pin', next: 'send' },
          send: { type: 'ACTION', flow: 'BALANCE', onSuccess: 'done', onFailure: 'done' },
          done: { type: 'END', text: t('ধন্যবাদ', 'Thank you') },
        },
      };
      const r = await MW.admin.previewMenu('ops-e2e-maker01', { menu, mno: 'gp' });
      S.status(400, r);
      S.code('MW-VAL-040', r);
      const issues = (r.body.details || []).map(d => d.issue);
      S.check('the dangling option is named', issues.includes('main: option 1 points to missing node nowhere'), JSON.stringify(issues));
      S.check('the stored PIN is named', issues.includes('pin: a PIN input is never stored'), JSON.stringify(issues));
    },
  },
];
