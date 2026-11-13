import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

function Kbd({ className, ...props }: ComponentProps<"kbd">) {
  return <kbd data-slot="kbd" className={cn("inline-flex h-5 min-w-5 items-center justify-center rounded border border-b-2 bg-card px-1 font-mono text-[11px] font-medium text-muted-foreground", className)} {...props} />;
}

export { Kbd };
