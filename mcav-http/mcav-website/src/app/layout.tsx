import type { Metadata } from 'next';
import './globals.css';
import React from "react";

export const metadata: Metadata = {
    title: {
        template: "%s | MCAV Audio",
        default: "MCAV Audio",
    },
    description: "A website to serve audio from the MCAV platform.",
    keywords: ['mcav', 'GitHub', 'audio', 'music', 'platform'],
    icons: {
        icon: 'favicon.ico?v=2'
    },
    robots: {
        index: true,
        follow: true,
    }
};

export default function RootLayout({
                                       children,
                                   }: {
    children: React.ReactNode;
}) {
    return (
        <html lang="en">
        <body>{children}</body>
        </html>
    );
}