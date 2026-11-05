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
import { execFileSync, spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, test } from "node:test";
import { fileURLToPath } from "node:url";

const SCRIPT = fileURLToPath(new URL("../third-party-notices.mjs", import.meta.url));
const ENCODING = "utf8";

const FIXTURE_PREFIX = "mcav-notices-";
const MODULES_DIRECTORY = "node_modules";
const MANIFEST_FILE = "package.json";
const LICENSE_FILE = "LICENSE";
const OUTPUT_FILE = "THIRD-PARTY-NOTICES.txt";

const ALPHA = "alpha";
const ALPHA_VERSION = "1.0.0";
const ALPHA_LICENSE_TEXT = "Alpha may be shipped under the MIT license.";
const BETA = "beta";
const BETA_VERSION = "2.0.0";
const LICENSE = "MIT";

const RUN_TIMEOUT_MILLISECONDS = 10000;

const USAGE_EXIT_CODE = 2;

let directory;

const writeManifest = (packageDirectory, manifest) => {
    mkdirSync(packageDirectory, {recursive: true});
    writeFileSync(join(packageDirectory, MANIFEST_FILE), JSON.stringify(manifest));
};

const writeFixture = () => {
    directory = mkdtempSync(join(tmpdir(), FIXTURE_PREFIX));
    const alpha = join(directory, MODULES_DIRECTORY, ALPHA);
    const beta = join(directory, MODULES_DIRECTORY, BETA);
    writeManifest(directory, {name: "fixture", dependencies: {[ALPHA]: ALPHA_VERSION}});
    writeManifest(alpha, {name: ALPHA, version: ALPHA_VERSION, license: LICENSE, dependencies: {[BETA]: BETA_VERSION}});
    writeManifest(beta, {name: BETA, version: BETA_VERSION, license: LICENSE, dependencies: {[ALPHA]: ALPHA_VERSION}});
    writeFileSync(join(alpha, LICENSE_FILE), ALPHA_LICENSE_TEXT);
};

const removeFixture = () => {
    rmSync(directory, {recursive: true, force: true});
};

const writeNotices = () => {
    const output = join(directory, OUTPUT_FILE);
    execFileSync(process.execPath, [SCRIPT, output], {cwd: directory, timeout: RUN_TIMEOUT_MILLISECONDS});
    return readFileSync(output, ENCODING);
};

before(writeFixture);
after(removeFixture);

test("should write a section for every shipped package", () => {
    const notices = writeNotices();

    assert.ok(notices.includes(`${ALPHA}@${ALPHA_VERSION} (${LICENSE})\n\n${ALPHA_LICENSE_TEXT}\n`), notices);
    assert.ok(notices.includes(`${BETA}@${BETA_VERSION} (${LICENSE})`), notices);
});

test("should say when a package carries no license file", () => {
    const notices = writeNotices();

    assert.ok(notices.includes(`${BETA}@${BETA_VERSION} (${LICENSE})\n\n${LICENSE}; the package carries no license file\n`), notices);
});

test("should finish on a dependency cycle", () => {
    const notices = writeNotices();

    assert.ok(notices.includes("\n2 packages, each with its license"), notices);
});

test("should exit with the usage code without an output path", () => {
    const run = spawnSync(process.execPath, [SCRIPT], {cwd: directory, encoding: ENCODING, timeout: RUN_TIMEOUT_MILLISECONDS});

    assert.equal(run.status, USAGE_EXIT_CODE, run.stderr);
});
