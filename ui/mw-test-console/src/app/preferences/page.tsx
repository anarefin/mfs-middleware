import { FillScope } from "@/components/fill-scope";
import { HowToTest } from "@/components/how-to-test";
import { PageHeader } from "@/components/page-header";
import { Preferences } from "./preferences";

export default function PreferencesPage() {
  return (
    <>
      <PageHeader
        title="Saved billers & favourites"
        description="The signed-in wallet's saved billers, favourite numbers and notification inbox (GET /app/v1/notifications). Nothing moves."
      />
      <HowToTest page="preferences" />
      <FillScope page="preferences">
        <Preferences />
      </FillScope>
    </>
  );
}
