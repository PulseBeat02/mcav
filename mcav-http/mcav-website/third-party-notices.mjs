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

const OUTPUT_ARGUMENT_INDEX = 2;

const USAGE_EXIT_CODE = 2;

const MODULES_DIRECTORY = "node_modules";
const MANIFEST_FILE = "package.json";
const ENCODING = "utf8";

const LICENSE_SEPARATOR = "\n\n";
const SECTION_RULE_WIDTH = 80;
const SECTION_SEPARATOR = `\n${"-".repeat(SECTION_RULE_WIDTH)}\n\n`;

const resolvePackage = (name, fromDirectory) => {
    for (let directory = fromDirectory; ; directory = dirname(directory)) {
        const candidate = join(directory, MODULES_DIRECTORY, name);
        if (existsSync(join(candidate, MANIFEST_FILE))) {
            return realpathSync(candidate);
        }
        if (dirname(directory) === directory) {
            return undefined;
        }
    }
};

const findRequiredDependencies = manifest => {
    const optionalPeers = manifest.peerDependenciesMeta ?? {};
    const peers = Object.keys(manifest.peerDependencies ?? {}).filter(name => !optionalPeers[name]?.optional);
    return [...Object.keys(manifest.dependencies ?? {}), ...peers];
};

const readManifest = directory => {
    const manifestPath = join(directory, MANIFEST_FILE);
    const text = readFileSync(manifestPath, ENCODING);
    return JSON.parse(text);
};

const findShippedPackages = () => {
    const root = readManifest(process.cwd());
    const shipped = new Map();
    const pending = Object.keys(root.dependencies ?? {}).map(name => [name, process.cwd()]);
    while (pending.length > 0) {
        const [name, fromDirectory] = pending.pop();
        const path = resolvePackage(name, fromDirectory);
        if (path === undefined || shipped.has(path)) {
            continue;
        }

        const manifest = readManifest(path);
        shipped.set(path, manifest);
        for (const dependency of findRequiredDependencies(manifest)) {
            pending.push([dependency, path]);
        }
    }

    return shipped;
};

const formatSection = (path, manifest) => {
    const license = manifest.license ?? "see the package";
    const texts = readdirSync(path)
        .filter(file => LICENSE_FILE.test(file))
        .sort()
        .map(file => readFileSync(join(path, file), ENCODING).trim());
    const text = texts.length > 0 ? texts.join(LICENSE_SEPARATOR) : `${license}; the package carries no license file`;
    return `${manifest.name}@${manifest.version} (${license})\n\n${text}\n`;
};

const output = process.argv[OUTPUT_ARGUMENT_INDEX];
if (!output) {
    console.error("Usage: node third-party-notices.mjs <output file>");
    process.exit(USAGE_EXIT_CODE);
}

const shipped = findShippedPackages();
const sections = [...shipped]
    .map(([path, manifest]) => formatSection(path, manifest))
    .sort();

const header =
    "The audio web page of mcav-http is built from the npm packages below; their code ships, minified, in the jar.\n" +
    `${sections.length} packages, each with its license as the package carries it.\n`;
writeFileSync(output, [header, ...sections].join(SECTION_SEPARATOR));
console.log(`Wrote ${output} with the notices of ${sections.length} packages.`);
