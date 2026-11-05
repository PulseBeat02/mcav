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

const findEntrySources = (folder, entry) => {
    const path = join(folder, entry.name);
    if (entry.isDirectory()) {
        return findSources(path);
    }

    if (SOURCE_FILE.test(entry.name)) {
        return [path];
    }

    return [];
};

const findSources = folder => {
    const entries = readdirSync(folder, {withFileTypes: true});
    return entries.flatMap(entry => findEntrySources(folder, entry));
};

const readManifest = directory => {
    const path = join(directory, MANIFEST_FILE);
    const text = readFileSync(path, ENCODING);
    return JSON.parse(text);
};

const findImportedNames = file => {
    const source = readFileSync(file, ENCODING);
    const imports = [...source.matchAll(IMPORT)];
    return imports.map(([, name]) => name);
};

const findPeerNames = name => {
    const packageManifest = readManifest(join(ROOT, MODULES_DIRECTORY, name));
    const peers = packageManifest.peerDependencies ?? {};
    return Object.keys(peers);
};

test("should use every dependency in the website, directly or as a peer of one it imports", () => {
    const rootManifest = readManifest(ROOT);
    const declared = Object.keys(rootManifest.dependencies);
    const files = findSources(join(ROOT, SOURCE_DIRECTORY));
    const imported = new Set(files.flatMap(findImportedNames));
    const importedDependencies = declared.filter(name => imported.has(name));
    const peers = new Set(importedDependencies.flatMap(findPeerNames));

    const unused = declared.filter(name => !imported.has(name) && !peers.has(name));
    assert.deepEqual(unused, []);
});
