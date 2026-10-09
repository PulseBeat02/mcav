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

import {createHash} from "node:crypto";
import {readdirSync, readFileSync, statSync} from "node:fs";
import {join, relative, sep} from "node:path";
import type {NextConfig} from "next";

const SOURCES = [
    "src",
    "public",
    "package.json",
    "package-lock.json",
    "next.config.ts",
    "third-party-notices.mjs",
    "tsconfig.json",
    "postcss.config.mjs"
];

// Next.js names the folder of a build's manifests after its build id, a random one unless told otherwise, so two builds
// of the same sources would make two different jars. An id derived from the sources makes the same website from the
// same sources, and a changed website still gets a new folder name, which keeps browsers from using stale manifests.
function sourcesBuildId(): string {
    const files: string[] = [];
    const collect = (path: string): void => {
        if (statSync(path).isDirectory()) {
            readdirSync(path).sort().forEach(name => collect(join(path, name)));
        } else {
            files.push(path);
        }
    };
    SOURCES.forEach(collect);
    const hash = createHash("sha256");
    for (const file of files) {
        hash.update(relative(".", file).split(sep).join("/"));
        hash.update("\0");
        hash.update(readFileSync(file));
    }
    return hash.digest("hex").slice(0, 20);
}

const nextConfig: NextConfig = {
    output: 'export',
    generateBuildId: async () => sourcesBuildId()
};

export default nextConfig;
