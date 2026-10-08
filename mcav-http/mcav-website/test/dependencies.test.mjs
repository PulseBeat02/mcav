import assert from 'node:assert/strict';
import {readdirSync, readFileSync} from 'node:fs';
import {join} from 'node:path';
import {test} from 'node:test';

const ROOT = new URL('..', import.meta.url).pathname;
const IMPORT = /(?:from\s+|import\s*\(\s*|require\s*\(\s*)['"]((?:@[^'"/]+\/)?[^'"/]+)/g;

function sources(folder) {
    return readdirSync(folder, {withFileTypes: true}).flatMap((entry) => {
        const path = join(folder, entry.name);
        return entry.isDirectory() ? sources(path) : /\.(tsx?|jsx?|mjs|css)$/.test(entry.name) ? [path] : [];
    });
}

function manifest(name) {
    return JSON.parse(readFileSync(join(ROOT, 'node_modules', name, 'package.json'), 'utf8'));
}

test('every dependency is imported by the website, or is a peer of one that is', () => {
    const declared = Object.keys(JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf8')).dependencies);
    const used = new Set();
    for (const file of sources(join(ROOT, 'src'))) {
        for (const [, name] of readFileSync(file, 'utf8').matchAll(IMPORT)) {
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
    assert.deepEqual(declared.filter((name) => !used.has(name)), []);
});
