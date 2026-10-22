/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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

/**
 * Wraps every page of the website in the document's html and body elements.
 *
 * @param properties the layout properties, whose children are the page to wrap
 * @returns the document that holds the page
 */
const RootLayout = ({children}: RootLayoutProperties): React.JSX.Element => {
    return (
        <html lang="en">
        <body>{children}</body>
        </html>
    );
};

export default RootLayout;
