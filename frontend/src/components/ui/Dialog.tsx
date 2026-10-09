"use client";

import { useEffect, useRef, type ReactNode } from "react";
import { Button } from "./primitives";

/**
 * Modal built on the native <dialog>: the browser traps focus, handles Escape and returns focus to the
 * element that opened it. Content is mounted only while open, so forms start fresh every time.
 */
export function Dialog({
  open,
  onClose,
  title,
  children,
  wide = false,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  wide?: boolean;
}) {
  if (!open) return null;
  return (
    <DialogInner onClose={onClose} title={title} wide={wide}>
      {children}
    </DialogInner>
  );
}

function DialogInner({ onClose, title, children, wide }: { onClose: () => void; title: string; children: ReactNode; wide: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const d = ref.current;
    if (d && !d.open) d.showModal();
  }, []);

  return (
    <dialog
      ref={ref}
      aria-labelledby="dialog-title"
      onClose={onClose}
      onClick={(e) => {
        if (e.target === ref.current) ref.current?.close(); // click on the backdrop
      }}
      className={`m-auto max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] rounded-2xl border border-line bg-surface p-0 text-ink shadow-xl ${wide ? "max-w-3xl" : "max-w-md"}`}
    >
      <div className="flex items-center justify-between gap-4 border-b border-line bg-brand-soft/70 px-5 py-3.5">
        <h2 id="dialog-title" className="font-display text-xl font-semibold text-navy">
          {title}
        </h2>
        <button type="button" onClick={() => ref.current?.close()} aria-label="Close" className="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-2xl leading-none text-muted transition-colors hover:bg-surface/80 hover:text-ink">
          ×
        </button>
      </div>
      <div className="max-h-[calc(100dvh-7rem)] overflow-y-auto px-5 py-5">{children}</div>
    </dialog>
  );
}

export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel,
  onConfirm,
  onCancel,
  destructive = false,
}: {
  open: boolean;
  title: string;
  message: ReactNode;
  confirmLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
  destructive?: boolean;
}) {
  return (
    <Dialog open={open} onClose={onCancel} title={title}>
      <div className="space-y-5">
        <div className="text-sm">{message}</div>
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
          <Button variant={destructive ? "danger" : "primary"} onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </Dialog>
  );
}
