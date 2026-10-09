"use client";

import { ReviewerArea } from "@/components/console/ReviewerArea";

export default function Layout({ children }: { children: React.ReactNode }) {
  return <ReviewerArea role="DIRECTOR">{children}</ReviewerArea>;
}
