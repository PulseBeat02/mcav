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

import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";

const ROOT = new URL("..", import.meta.url).pathname;
const SOURCE_DIRECTORY = "src";
const MODULES_DIRECTORY = "node_modules";
const MANIFEST_FILE = "package.json";
const ENCODING = "utf8";

const SOURCE_FILE = /\.(tsx?|jsx?|mjs|css)$/;
const IMPORT = /(?:from\s+|import\s*\(\s*|require\s*\(\s*)['"]((?:@[^'"/]+\/)?[^'"/]+)/g;

const entrySources = (folder, entry) => {
    const path = join(folder, entry.name);
    if (entry.isDirectory()) {
        return sources(path);
    }

    if (SOURCE_FILE.test(entry.name)) {
        return [path];
    }

    return [];
};

const sources = folder => {
    const entries = readdirSync(folder, {withFileTypes: true});
    return entries.flatMap(entry => entrySources(folder, entry));
};

const manifest = name => {
    return JSON.parse(readFileSync(join(ROOT, MODULES_DIRECTORY, name, MANIFEST_FILE), ENCODING));
};

test("every dependency is imported by the website, or is a peer of one that is", () => {
    const declared = Object.keys(JSON.parse(readFileSync(join(ROOT, MANIFEST_FILE), ENCODING)).dependencies);
    const used = new Set();
    for (const file of sources(join(ROOT, SOURCE_DIRECTORY))) {
        for (const [, name] of readFileSync(file, ENCODING).matchAll(IMPORT)) {
            if (declared.includes(name)) {
                used.add(name);
            }
        }
    }
    for (const name of [...used]) {
        for (const peer of Object.keys(manifest(name).peerDependencies ?? {})) {
            if (declared.includes(peer)) {
                used.add(peer);
            }
        }
    }
    assert.deepEqual(declared.filter(name => !used.has(name)), []);
});
