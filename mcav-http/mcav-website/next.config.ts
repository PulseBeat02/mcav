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

import { type BinaryToTextEncoding, createHash, type Hash } from "node:crypto";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import type { NextConfig } from "next";

const SOURCES: string[] = [
    "src",
    "public",
    "package.json",
    "pnpm-lock.yaml",
    "next.config.ts",
    "third-party-notices.mjs",
    "tsconfig.json",
    "postcss.config.mjs"
];

const HASH_ALGORITHM: string = "sha256";
const HASH_ENCODING: BinaryToTextEncoding = "hex";

const BUILD_ID_LENGTH: number = 20;

const CURRENT_DIRECTORY: string = ".";
const PATH_SEPARATOR: string = "/";
const PATH_TERMINATOR: string = "\0";

// Next.js names the folder of a build's manifests after its build id, a random one unless told otherwise, so two builds
// of the same sources would make two different jars. An id derived from the sources makes the same website from the
// same sources, and a changed website still gets a new folder name, which keeps browsers from using stale manifests.
const sourcesBuildId = (): string => {
    const files: string[] = [];
    const collect = (path: string): void => {
        if (statSync(path).isDirectory()) {
            readdirSync(path).sort().forEach((name: string) => collect(join(path, name)));
        } else {
            files.push(path);
        }
    };
    SOURCES.forEach(collect);
    const hash: Hash = createHash(HASH_ALGORITHM);
    for (const file of files) {
        hash.update(relative(CURRENT_DIRECTORY, file).split(sep).join(PATH_SEPARATOR));
        hash.update(PATH_TERMINATOR);
        hash.update(readFileSync(file));
    }
    return hash.digest(HASH_ENCODING).slice(0, BUILD_ID_LENGTH);
};

const nextConfig: NextConfig = {
    output: "export",
    generateBuildId: sourcesBuildId
};

export default nextConfig;
