import { cva, type VariantProps } from "class-variance-authority";
import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex w-fit shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-semibold [&>svg]:size-3",
  {
    variants: {
      tone: {
        neutral: "bg-secondary text-secondary-foreground",
        good: "bg-good-bg text-good",
        bad: "bg-bad-bg text-bad",
        warn: "bg-warn-bg text-warn",
        info: "bg-info-bg text-info",
        rev: "bg-rev-bg text-rev",
        outline: "border text-foreground",
      },
      dot: { true: "before:size-1.5 before:rounded-full before:bg-current before:content-['']", false: "" },
    },
    defaultVariants: { tone: "neutral", dot: false },
  },
);

function Badge({ className, tone, dot, ...props }: ComponentProps<"span"> & VariantProps<typeof badgeVariants>) {
  return <span data-slot="badge" className={cn(badgeVariants({ tone, dot }), className)} {...props} />;
}

export { Badge, badgeVariants };
