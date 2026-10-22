import type { Metadata } from "next";
import "./globals.css";
import React from "react";

export const metadata: Metadata = {
    title: {
        template: "%s | MCAV Audio",
        default: "MCAV Audio",
    },
    description: "A website to serve audio from the MCAV platform.",
    keywords: ["mcav", "GitHub", "audio", "music", "platform"],
    icons: {
        icon: "favicon.ico?v=2"
    },
    robots: {
        index: true,
        follow: true,
    }
};

type RootLayoutProperties = {
    children: React.ReactNode;
};

const RootLayout = ({children}: RootLayoutProperties): React.JSX.Element => {
    return (
        <html lang="en">
        <body>{children}</body>
        </html>
    );
};

export default RootLayout;
