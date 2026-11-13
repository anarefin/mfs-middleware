import { Loader2, type LucideIcon } from "lucide-react";
import type { ComponentProps, ReactNode } from "react";
import { CardContent, CardHeader, CardSection, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";

/** A page section as a card: a header (small icon + h2 title, optional actions) and its content. */
export function Panel({
  title,
  icon: Icon,
  actions,
  className,
  contentClassName,
  children,
  ...props
}: Omit<ComponentProps<"section">, "title"> & { title?: ReactNode; icon?: LucideIcon; actions?: ReactNode; contentClassName?: string }) {
  return (
    <CardSection className={className} {...props}>
      {title ? (
        <CardHeader>
          <CardTitle className="flex-1">{Icon ? <Icon /> : null}{title}</CardTitle>
          {actions}
        </CardHeader>
      ) : null}
      <CardContent className={cn("flex flex-col gap-3", contentClassName)}>{children}</CardContent>
    </CardSection>
  );
}

/** A small sub-heading inside a panel. */
export function SubHeading({ className, ...props }: ComponentProps<"h3">) {
  return <h3 className={cn("m-0 text-xs font-semibold uppercase tracking-wide text-muted-foreground", className)} {...props} />;
}

/** Muted helper text. */
export function Hint({ className, ...props }: ComponentProps<"p">) {
  return <p className={cn("m-0 text-xs text-muted-foreground", className)} {...props} />;
}

/** "working…" while an action runs. */
export function Busy({ children }: { children: ReactNode }) {
  return (
    <p className="m-0 flex items-center gap-2 text-sm font-medium text-info" role="status">
      <Loader2 className="size-4 animate-spin" />
      {children}
    </p>
  );
}

/** The placeholder on the right before anything ran. */
export function EmptyResult({ children }: { children: ReactNode }) {
  return <div className="rounded-lg border border-dashed px-6 py-10 text-center text-sm text-muted-foreground">{children}</div>;
}

/** Form card left (about 420px), results right; one column on narrow screens. */
export function TwoColumns({ className, ...props }: ComponentProps<"div">) {
  return <div className={cn("grid items-start gap-5 lg:grid-cols-[420px_minmax(0,1fr)]", className)} {...props} />;
}

/** A wrapping row of controls aligned at their bottom edge. */
export function Row({ className, ...props }: ComponentProps<"div">) {
  return <div className={cn("flex flex-wrap items-end gap-2 [&>label]:min-w-[120px] [&>label]:flex-1", className)} {...props} />;
}
