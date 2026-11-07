import { Inbox } from "./inbox";

export default function InboxPage() {
  return (
    <>
      <h1>SMS & email</h1>
      <p className="lead">
        What mw-notify sent: SMS over SMPP to the SMSC simulator (GSM-7 for English, UCS-2 for Bangla, delivery reports) and email
        to GreenMail. Check the language, the masked numbers (<code>0171****001</code>) and that each flow notified the right parties once.
      </p>
      <Inbox />
    </>
  );
}
