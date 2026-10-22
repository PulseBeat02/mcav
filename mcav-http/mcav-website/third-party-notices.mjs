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

import { existsSync, readdirSync, readFileSync, realpathSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";

const LICENSE_FILE = /^(licen[cs]e|copying|notice)([.-].*)?$/i;

const output = process.argv[2];
if (!output) {
    console.error("usage: node third-party-notices.mjs <output file>");
    process.exit(2);
}

function resolvePackage(name, fromDir) {
    for (let dir = fromDir; ; dir = dirname(dir)) {
        const candidate = join(dir, "node_modules", name);
        if (existsSync(join(candidate, "package.json"))) {
            return realpathSync(candidate);
        }
        if (dirname(dir) === dir) {
            return null;
        }
    }
}

function requiredDependencies(manifest) {
    const optionalPeers = manifest.peerDependenciesMeta ?? {};
    const peers = Object.keys(manifest.peerDependencies ?? {}).filter((name) => !optionalPeers[name]?.optional);
    return [...Object.keys(manifest.dependencies ?? {}), ...peers];
}

const root = JSON.parse(readFileSync("package.json", "utf8"));
const shipped = new Map();
const pending = Object.keys(root.dependencies ?? {}).map((name) => [name, process.cwd()]);
while (pending.length > 0) {
    const [name, fromDir] = pending.pop();
    const path = resolvePackage(name, fromDir);
    if (path === null || shipped.has(path)) {
        continue;
    }
    const manifest = JSON.parse(readFileSync(join(path, "package.json"), "utf8"));
    shipped.set(path, manifest);
    for (const dependency of requiredDependencies(manifest)) {
        pending.push([dependency, path]);
    }
}

const sections = [];
for (const [path, manifest] of shipped) {
    const license = manifest.license ?? "see the package";
    const texts = readdirSync(path)
        .filter((file) => LICENSE_FILE.test(file))
        .sort()
        .map((file) => readFileSync(join(path, file), "utf8").trim());
    const text = texts.length > 0 ? texts.join("\n\n") : `${license}; the package carries no license file`;
    sections.push(`${manifest.name}@${manifest.version} (${license})\n\n${text}\n`);
}
sections.sort();
const header =
    "The audio web page of mcav-http is built from the npm packages below; their code ships, minified, in the jar.\n" +
    `${sections.length} packages, each with its license as the package carries it.\n`;
writeFileSync(output, [header, ...sections].join("\n" + "-".repeat(80) + "\n\n"));
console.log(`Wrote the notices of ${sections.length} packages to ${output}`);
