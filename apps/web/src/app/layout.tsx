import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import { AppShell } from "@/components/AppShell";
import "./globals.css";

export const metadata: Metadata = {
  title: "Aviation Trip Optimizer",
  description: "Flugreisen nach Preis, Reisezeit, Aufenthaltsdauer und Flugzeugerlebnis optimieren.",
  manifest: `${process.env.NEXT_PUBLIC_BASE_PATH || ""}/app.webmanifest`,
  appleWebApp: { capable: true, title: "Trip Optimizer", statusBarStyle: "black-translucent" },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
  themeColor: "#0b1a33",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="de">
      <body>
        <AppShell>{children}</AppShell>
      </body>
    </html>
  );
}
