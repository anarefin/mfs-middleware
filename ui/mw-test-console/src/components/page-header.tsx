import type { ReactNode } from "react";

/** Page title, one line on what the page is for, and the page's own buttons on the right. */
export function PageHeader({ title, description, actions }: { title: ReactNode; description?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end gap-3">
      <div className="min-w-0 flex-1">
        <h1 className="m-0 text-xl font-semibold tracking-tight">{title}</h1>
        {description ? <p className="m-0 mt-1 text-muted-foreground">{description}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  );
}
