// A plain value (a username) shown as the secrets are, with a copy button; never masked.

import { CopyButton } from "@/components/copy-button";
import { cn } from "@/lib/utils";

export function CopyableText({ value, testId, className }: { value: string; testId?: string; className?: string }) {
  return (
    <span className={cn("inline-flex min-w-0 items-center gap-0.5", className)}>
      <code className="truncate rounded bg-muted px-1.5 py-0.5 font-mono text-xs" data-testid={testId}>{value}</code>
      <CopyButton value={value} label={`Copy ${value}`} testId={testId ? `copy-${testId}` : undefined} />
    </span>
  );
}

/** A username with its copy button: test ids `user-<username>` and `copy-user-<username>`. */
export function Username({ name }: { name: string }) {
  return <CopyableText value={name} testId={`user-${name}`} />;
}
