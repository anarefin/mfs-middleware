import { PageHeader } from "@/components/page-header";
import { Inbox } from "./inbox";

export default function InboxPage() {
  return (
    <>
      <PageHeader
        title="SMS & email"
        description={
          <>
            What mw-notify sent: SMS over SMPP to the SMSC simulator (GSM-7 for English, UCS-2 for Bangla, delivery reports) and email
            to GreenMail. Check the language, the masked numbers (<code className="font-mono text-xs">0171****001</code>) and that each flow notified the right parties once.
          </>
        }
      />
      <Inbox />
    </>
  );
}
