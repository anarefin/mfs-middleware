"use client";

import { Loader2, Star } from "lucide-react";
import { useActionState } from "react";
import { Hint, Panel, Row } from "@/components/section";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { preferences } from "../actions/preferences";
import { Result } from "../components/result";

/** Saved billers, favourites and the inbox of one wallet (ticket 107a). */
export function Preferences() {
  const [state, run, pending] = useActionState(preferences, null);
  return (
    <Panel title="Saved billers, favourites, inbox" icon={Star}>
      <form action={run} className="flex flex-col gap-3" aria-busy={pending}>
        <Row>
          <Field label="User"><Input name="user" defaultValue="customer01" className="font-mono" /></Field>
          <Field label="Biller code"><Input name="billerCode" defaultValue="DESCO" className="font-mono" /></Field>
          <Field label="Account number"><Input name="accountNo" defaultValue="9876543210" className="font-mono" /></Field>
          <Field label="Nickname"><Input name="nickname" defaultValue="home" /></Field>
        </Row>
        <Row>
          <Field label="Favourite number"><Input name="msisdn" defaultValue="01711000002" className="font-mono" /></Field>
          <Field label="Label"><Input name="label" defaultValue="Amma" /></Field>
          <Field label="Id"><Input name="id" placeholder="id to remove or mark read" className="font-mono" /></Field>
        </Row>
        <Row>
          <Button type="submit" name="op" value="billers" disabled={pending}>Saved billers</Button>
          <Button type="submit" name="op" value="saveBiller" disabled={pending}>Save biller</Button>
          <Button type="submit" name="op" value="deleteBiller" variant="outline" disabled={pending}>Remove biller</Button>
          <Button type="submit" name="op" value="favourites" disabled={pending}>Favourites</Button>
          <Button type="submit" name="op" value="saveFavourite" disabled={pending}>Save favourite</Button>
          <Button type="submit" name="op" value="deleteFavourite" variant="outline" disabled={pending}>Remove favourite</Button>
          <Button type="submit" name="op" value="inbox" disabled={pending}>Inbox</Button>
          <Button type="submit" name="op" value="inboxRead" variant="outline" disabled={pending}>Mark read</Button>
          {pending ? <Loader2 className="animate-spin" /> : null}
        </Row>
        <Hint>The wallet is the signed-in user. Another wallet&apos;s id is 404. The owner sees the account number and the favourite number; the inbox MSISDN is masked and OTP messages are left out.</Hint>
      </form>
      <Result state={state} />
    </Panel>
  );
}
