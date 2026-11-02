# Audio Web Player

The audio web player of `mcav-http`: a Next.js page that plays the sound a server streams over the WebSocket `/audio`,
with the title and thumbnail of the current media from `/media`, and a visualizer. `mcav-http` serves it from its jar
([mcav-docs/library/http.md](../../mcav-docs/library/http.md)); you never deploy it on its own.

## Building

The Gradle build of `mcav-http` builds the page with the Node.js it downloads and the pnpm pinned in `package.json`
(`pnpmProjectInstall`, then `buildWebsite`), exports it as static files (`output: "export"` in `next.config.ts`) into
`out`, and puts them into the jar's `mcav/http/website` folder, with `out/THIRD-PARTY-NOTICES.txt` from
`third-party-notices.mjs`. By hand, with Node.js 24 and `corepack enable`:

```bash
pnpm install --frozen-lockfile
pnpm run build
```

`pnpm run dev` serves the page with live reload at `http://localhost:3000`; the WebSocket and `/media` it calls are
those of a running `mcav-http` server, so without one it shows no media and connects to nothing.

## Checks

- `pnpm test` runs the tests in `test/` with Node.js's test runner. They load the page's own `startPlayback` and
  `stopPlayback` callbacks from `src/app/page.tsx` and run them against fake sockets, sound processors and timers. The
  Gradle task `testWebsite`, part of `check`, runs them on the build's Node.js.
- `pnpm run lint` runs ESLint on `src`.
