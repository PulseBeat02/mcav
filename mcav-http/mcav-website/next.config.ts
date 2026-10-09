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
