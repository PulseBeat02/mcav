# Manual test: watching mcav on a real server

This is the checklist for seeing mcav play media with your own eyes, with a real Minecraft client, rather than
trusting the automated tests. It takes about fifteen minutes the first time, and about two minutes afterwards.

You need a Minecraft **26.2** client. The sandbox plugin is built against Paper 26.2, and a 26.2 server refuses
older clients unless you add ViaBackwards, so a client of any other version will simply be rejected at login.

## 1. Forward the server port to your PC

The devbox only listens on its own loopback address, so the game port is not reachable from outside. Open a
tunnel from the machine your Minecraft client runs on and leave it running:

```sh
ssh -N devbox-mc
```

`devbox-mc` is the host entry that forwards local port 25565 to the devbox's 25565. If you do not have it yet, add
this to `~/.ssh/config` on your PC:

```
Host devbox-mc
    HostName <devbox>
    User dev
    LocalForward 25565 127.0.0.1:25565
```

## 2. Start the sandbox server on the devbox

```sh
cd sandbox/plugin
../../gradlew runServer
```

The first start takes several minutes: it downloads Paper, then the plugin asks Gremlin to download the mcav
modules, the JavaCV natives, VLC and yt-dlp. Wait for `Done (…)! For help, type "help"` and, just before it,
`MCAV loaded in … ms`.

> **Testing your own changes.** `runServer` downloads the **published** `1.0.0-SNAPSHOT` modules, so by default you
> are not watching your working tree at all. To run the modules of your build, publish them into a local repository
> and let the server download them from there:
>
> ```sh
> # from the repository root, build and publish before starting either server:
> ./gradlew :sandbox:plugin:shadowJar -Pmcav.e2e=true -Pmcav.e2e.repositoryPort=8765
> python3 -m http.server 8765 --bind 127.0.0.1 --directory build/e2e-repository
> # then, from the repository root in a second shell:
> ./gradlew :sandbox:plugin:runServer -Pmcav.e2e=true -Pmcav.e2e.repositoryPort=8765
> ```
>
> The port is baked into the plugin's `dependencies.txt` as the first repository, so the server asks it first. You
> can confirm it worked: the request log of that little HTTP server must show `mcav-bukkit-1.0.0-<timestamp>.jar`
> being fetched. This is the same mechanism the `e2eTest` task uses.

Let a bot or yourself join without a Mojang account by setting `online-mode=false` in `run/server.properties`
before starting, and accept the EULA in `run/eula.txt` (`eula=true`).

## 3. Join

Connect your client to `localhost:25565`. You should appear in a flat world.

## 4. Build a screen and play something

Run these in chat with a leading slash, so the relative coordinates use your position. Grant yourself
permission from the server console first with `op <yourname>`.

```
mcav screen "15x9" 0 black_concrete ~ ~ ~
mcav video map @a VLC NONE "1920x1080" "15x9" 0 FLOYD_STEINBERG "" "/absolute/path/to/mcav.mp4"
```

The first command builds a wall of 135 item frames holding maps `0`–`134`, starting at the top left as you face
it. The second plays the file onto exactly those maps. Stop it with:

```
mcav video release
```

Inspect a still image, release it, then try video with browser audio. Run each command separately:

```
mcav image map @a "1920x1080" "15x9" 0 FLOYD_STEINBERG /absolute/path/to/picture.png
mcav image release
mcav video map @a VLC HTTP_SERVER "1920x1080" "15x9" 0 FLOYD_STEINBERG "" "/absolute/path/to/mcav.mp4"
```

> **Image paths may be quoted or unquoted.** Image commands consume the rest of the line and strip one
> enclosing pair of double quotes. Both forms support paths containing spaces. Use quotes for video MRLs
> containing spaces. Empty and malformed image sources still report `Invalid MRL!`.

With `HTTP_SERVER` audio, the console prints `The audio web page is available at http://localhost:8080/`; open
that page in a browser (forward port 8080 the same way) and press play. Listen for audio and compare its timing
with the video; a successful connection alone does not verify synchronization.

## 5. What to look for

- **The whole wall fills.** Every map in the grid should show part of the picture, including the outer rows and
  columns. If a map stays blank, check its ID, frame facing and duplicate frames as well as packet delivery.
  Rebuild the same screen once and verify it remains visible with one item frame per cell.
- **No tearing between maps.** Watch motion across tile boundaries. Packets are bundled, but large updates can be split
  and delta budgeting can defer tiles. A bundle is not a guarantee that the whole wall displays one decoded
  frame atomically; record any visible split or stale tiles.
- **Motion is smooth, or evenly slow.** Playback that stutters in bursts — a second of motion, then several
  seconds frozen — can come from decoding, rendering or contention. The measured `mcav.mp4` fixture is
  2560x1440 AV1 at 60 fps. Compare it with a smaller H.264 file:
  ```sh
  ffmpeg -i mcav.mp4 -t 120 -vf scale=854:480 -r 30 -c:v libx264 -preset veryfast -crf 28 cheap.mp4
  ```
  If `cheap.mp4` is smooth and `mcav.mp4` is not, that supports a workload or codec bottleneck; it does not
  isolate decoder time from every other stage. The playing message reports state, not continuing frame progress.
- **The maps clear on release.** `mcav video release` should blank the wall, not leave the last frame frozen on it.
- **The console stays quiet.** No `ERROR`, no stack traces, during playback or on release.

## 6. Capturing client-side errors

The server log is `sandbox/plugin/run/logs/latest.log`. The client's own log is the one that shows rendering and
protocol problems, and it is not on the devbox:

- **Vanilla launcher:** `.minecraft/logs/latest.log` (Windows `%APPDATA%\.minecraft\logs\latest.log`, macOS
  `~/Library/Application Support/minecraft/logs/latest.log`, Linux `~/.minecraft/logs/latest.log`).
- **Prism / MultiMC:** the instance's `minecraft/logs/latest.log`, or the **Log** tab while it runs.
- Start the client from a terminal to watch it live; a client-side protocol failure usually shows up there as a
  disconnect with a decoder error naming the packet.

If the client disconnects during playback, copy the last twenty lines of both logs — the server log says what mcav
was sending, the client log says what it could not read.

## 7. Stop

```
mcav video release
stop
```

`stop` must end with the process exiting on its own. If it hangs, take a thread dump first — `jcmd <pid>
Thread.print` — because that is the evidence for a leaked non-daemon thread.
