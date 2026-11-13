import type { ComponentProps, ReactNode } from "react";
import { cn } from "@/lib/utils";

/** A label wrapping its control (the label text names the control: getByLabel("Reason")). */
function Field({ label, hint, className, children, ...props }: Omit<ComponentProps<"label">, "children"> & { label: ReactNode; hint?: ReactNode; children: ReactNode }) {
  return (
    <label data-slot="field" className={cn("flex min-w-0 flex-col gap-1.5 text-xs font-medium text-muted-foreground", className)} {...props}>
      {label}
      {children}
      {hint ? <span className="font-normal">{hint}</span> : null}
    </label>
  );
}

function InlineField({ className, children, ...props }: ComponentProps<"label">) {
  return (
    <label className={cn("inline-flex items-center gap-2 text-sm text-foreground", className)} {...props}>
      {children}
    </label>
  );
}

export { Field, InlineField };
