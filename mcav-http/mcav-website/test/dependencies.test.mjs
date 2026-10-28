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

const importedNames = file => {
    const source = readFileSync(file, ENCODING);
    const imports = [...source.matchAll(IMPORT)];
    return imports.map(([, name]) => name);
};

const peerNames = name => {
    const peers = manifest(name).peerDependencies ?? {};
    return Object.keys(peers);
};

test("should use every dependency in the website, directly or as a peer of one it imports", () => {
    const rootManifest = JSON.parse(readFileSync(join(ROOT, MANIFEST_FILE), ENCODING));
    const declared = Object.keys(rootManifest.dependencies);
    const files = sources(join(ROOT, SOURCE_DIRECTORY));
    const imported = new Set(files.flatMap(importedNames));
    const importedDependencies = declared.filter(name => imported.has(name));
    const peers = new Set(importedDependencies.flatMap(peerNames));

    const unused = declared.filter(name => !imported.has(name) && !peers.has(name));
    assert.deepEqual(unused, []);
});
