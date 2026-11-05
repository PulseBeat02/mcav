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
import { readdirSync, readFileSync, type Stats, statSync } from "node:fs";
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

const collectFiles = (path: string, files: string[]): void => {
    const stats: Stats = statSync(path);
    if (!stats.isDirectory()) {
        files.push(path);
        return;
    }

    const names: string[] = readdirSync(path);
    names.sort();
    for (const name of names) {
        const child: string = join(path, name);
        collectFiles(child, files);
    }
};

// Next.js names the manifest folder after the build id; one derived from the sources keeps the jar reproducible
const createBuildIdFromSources = (): string => {
    const files: string[] = [];
    for (const source of SOURCES) {
        collectFiles(source, files);
    }

    const hash: Hash = createHash(HASH_ALGORITHM);
    for (const file of files) {
        const relativePath: string = relative(CURRENT_DIRECTORY, file);
        const portablePath: string = relativePath.split(sep).join(PATH_SEPARATOR);
        hash.update(portablePath);
        hash.update(PATH_TERMINATOR);
        const content: Buffer = readFileSync(file);
        hash.update(content);
    }

    const digest: string = hash.digest(HASH_ENCODING);
    return digest.slice(0, BUILD_ID_LENGTH);
};

const nextConfiguration: NextConfig = {
    output: "export",
    generateBuildId: createBuildIdFromSources
};

export default nextConfiguration;
