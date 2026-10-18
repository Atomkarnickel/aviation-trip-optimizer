"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { APP_NAME, APP_VERSION } from "@/lib/config";

const NAV = [
  { href: "/", label: "Entdecken", icon: "M10 2a8 8 0 1 0 4.9 14.3l5.4 5.4 1.4-1.4-5.4-5.4A8 8 0 0 0 10 2Zm0 2a6 6 0 1 1 0 12 6 6 0 0 1 0-12Z" },
  { href: "/favoriten/", label: "Favoriten", icon: "M12 21s-7.5-4.6-9.6-9.2C.9 8.4 3 4.5 6.7 4.5c2 0 3.6 1.1 4.3 2.6.7-1.5 2.3-2.6 4.3-2.6 3.7 0 5.8 3.9 4.3 7.3C19.5 16.4 12 21 12 21Z" },
  { href: "/logbuch/", label: "Logbuch", icon: "M5 3h11l3 3v15H5V3Zm2 2v14h10V7h-2V5H7Zm2 4h6v2H9V9Zm0 4h6v2H9v-2Z" },
  { href: "/profil/", label: "Profil", icon: "M12 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10Zm0 2c-4.4 0-8 2.2-8 5v2h16v-2c0-2.8-3.6-5-8-5Z" },
  { href: "/about/", label: "About", icon: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm1 15h-2v-6h2v6Zm0-8h-2V7h2v2Z" },
];

function NavLinks() {
  const pathname = usePathname() || "/";
  const normalized = pathname.endsWith("/") ? pathname : `${pathname}/`;
  return (
    <>
      {NAV.map((item) => {
        const active = item.href === "/" ? normalized === "/" : normalized.startsWith(item.href);
        return (
          <Link key={item.href} href={item.href} className="nav-link" aria-current={active ? "page" : undefined}>
            <svg className="nav-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d={item.icon} />
            </svg>
            <span>{item.label}</span>
          </Link>
        );
      })}
    </>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="shell">
      <a className="skip-link" href="#inhalt">Zum Inhalt springen</a>
      <nav className="sidebar" aria-label="Hauptnavigation">
        <Link href="/" className="brand">
          <span className="brand-mark" aria-hidden="true">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="#fff"><path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5Z" /></svg>
          </span>
          {APP_NAME}
        </Link>
        <NavLinks />
        <div className="sidebar-foot">Web v{APP_VERSION}</div>
      </nav>
      <main id="inhalt" className="main">{children}</main>
      <nav className="bottom-nav" aria-label="Hauptnavigation (mobil)">
        <NavLinks />
      </nav>
    </div>
  );
}
