"use client";

import { useState } from "react";
import { Alert, Button } from "@/components/ui/primitives";
import { Dialog } from "@/components/ui/Dialog";
import { ROLE_LABEL } from "@/lib/labels";
import type { IssuedPassword } from "@/lib/types";

/**
 * Shows the old password after an account is created or its password reset. The server keeps only a hash of
 * what a person later chooses; the standard one is the same for everyone and is sent back in this response.
 */
export function OneTimePasswordDialog({
  issued,
  reason,
  onClose,
}: {
  issued: IssuedPassword | null;
  reason: "created" | "reset";
  onClose: () => void;
}) {
  return (
    <Dialog open={issued !== null} onClose={onClose} title={reason === "created" ? "Account created" : "Password reset"}>
      {issued && <Body issued={issued} reason={reason} />}
    </Dialog>
  );
}

function Body({ issued, reason }: { issued: IssuedPassword; reason: "created" | "reset" }) {
  const [copied, setCopied] = useState<"yes" | "no" | null>(null);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(issued.temporaryPassword);
      setCopied("yes");
    } catch {
      setCopied("no");
    }
  };

  return (
    <div className="space-y-4">
      <p className="text-sm">
        {reason === "created" ? "An account was created for" : "The password was set back to the standard one for"}{" "}
        <span className="font-semibold">{issued.email}</span> ({ROLE_LABEL[issued.role]}).
      </p>
      <div>
        <p className="mb-1 text-[13px] font-semibold tracking-wide">Old password</p>
        <code
          data-testid="temporary-password"
          className="block select-all rounded-sm border border-line-strong bg-canvas px-3 py-3 text-center font-mono text-2xl font-semibold tracking-[0.12em] text-navy"
        >
          {issued.temporaryPassword}
        </code>
      </div>
      <div className="flex items-center gap-3">
        <Button type="button" variant="secondary" size="sm" onClick={() => void copy()}>
          Copy password
        </Button>
        <span role="status" className="text-sm">
          {copied === "yes" && <span className="text-ok">Copied.</span>}
          {copied === "no" && <span className="text-bad">Could not copy; select the password above and copy it.</span>}
        </span>
      </div>
      <Alert tone="warn" title="They must choose their own password">
        Give it to the person privately. The first time they sign in they are asked to replace it with a password only they know.
      </Alert>
    </div>
  );
}
