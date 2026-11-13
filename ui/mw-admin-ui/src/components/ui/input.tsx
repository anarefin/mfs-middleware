import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

export const controlClass =
  "w-full min-w-0 rounded-md border border-input bg-card px-3 text-sm text-foreground shadow-xs transition-[color,box-shadow] outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive dark:bg-input/30";

function Input({ className, type, ...props }: ComponentProps<"input">) {
  return <input type={type} data-slot="input" className={cn(controlClass, "h-9 py-1 file:border-0 file:bg-transparent file:text-sm file:font-medium", className)} {...props} />;
}

function Textarea({ className, ...props }: ComponentProps<"textarea">) {
  return <textarea data-slot="textarea" className={cn(controlClass, "min-h-20 py-2 font-[inherit]", className)} {...props} />;
}

/** A styled native select: forms post as GET / server actions and Playwright drives it with selectOption. */
function NativeSelect({ className, ...props }: ComponentProps<"select">) {
  return <select data-slot="native-select" className={cn(controlClass, "h-9 appearance-auto py-1 pr-2", className)} {...props} />;
}

export { Input, NativeSelect, Textarea };
