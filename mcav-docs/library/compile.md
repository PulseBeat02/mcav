# Compiling MCAV

Compiling mcav is incredibly easy.

- Clone the repository from GitHub, and navigate to the directory:
```bash
git clone https://github.com/PulseBeat02/mcav.git
cd mcav
```

- Run the Gradle wrapper to build the project:

```bash
./gradlew build
```

- Profit! The plugin jar is `mcav-plugin/build/libs/mcav-plugin-<version>-all.jar`, currently
`mcav-plugin-1.0.0-v26.3-all.jar`.

## Build Options

Gradle runs on any JDK 17 or newer. The build needs a Java 25 toolchain, which Gradle downloads if the machine has none,
and it downloads a Node.js of its own for the code formatter and the web page of `mcav-http`. No credentials are
needed, except to publish. The project builds on any one of Windows, macOS or Linux: tests that need another operating
system, or a program the machine lacks (VLC, QEMU, a display), skip themselves. `build` also enforces the coverage lint,
and the code those skipped tests would have run shows up there as uncovered lines, so on such a machine build with
`-Pmcav.coverage=false`. The twelve Java modules using
`buildSrc/src/main/kotlin/mcav.module.gradle.kts` are `mcav-browser`, `mcav-bukkit`, `mcav-common`, `mcav-discord`,
`mcav-http`, `mcav-installer`, `mcav-lwjgl`, `mcav-mod`, `mcav-plugin`, `mcav-vm`, `mcav-vnc` and `mcav-voicechat`.
Their compilations run [Error Prone](https://errorprone.info/) and, for production code, the Checker Framework,
and must stay free of warnings. The coverage, property, fuzz and PIT rows below apply to these twelve modules;
`<module>` means one of their names.

`mcav-docs` builds the book. buildSrc has separate bootstrap tests (`./gradlew :buildSrc:test`).
`mcav-jcstress` uses the Java-library, formatting and lint conventions through
`buildSrc/src/main/kotlin/mcav.jcstress.gradle.kts`; it skips Checker Framework and removes `-Werror` from
`compileJava`, with no module coverage, property, fuzz or PIT tasks.

| Option | What it does |
|--------|---------------|
| `./gradlew build` | Compiles everything, checks formatting, runs the tests, the property tests, a replay of every fuzz input and the Python codec tests, checks the format of the MCV2 native sources, builds the documentation, and enforces the coverage lint and the source lints (`qualifiedNames`, `logMessages`, `variableNames`). |
| `./gradlew propertyTest`, `fuzzTest` | Runs only the property tests, or only the fuzz tests; `-Pfuzz.seconds=<n>` fuzzes each fuzz test for that long. |
| `./gradlew jcstressTest` | Runs the concurrency tests of `mcav-jcstress` on jcstress. |
| `./gradlew :mcav-docs:build` | Builds only the documentation, into `mcav-docs/build/html/index.html`. |
| `./gradlew spotlessApply` | Formats the sources; run before building after editing code. |
| `./gradlew spotlessRepositoryCheck` | Checks whitespace in the repository text files, including the version catalog, the tool checksums and the Python lock. |
| `-Pmcav.testJavaHome=<path to a Java 25 JDK>` | Runs the tests on another JDK, for machines whose JDK cannot load the bundled OpenCV natives. |
| `./gradlew coverageLint` | Prints every line and branch no test covers, as `file:line`. |
| `-Pmcav.coverage=false` | Turns off the coverage lint, which `check`, and so `build`, enforces by default; any other value keeps it on. |
| `-Pmcav.networkTests=true` | Also runs the tests that download from the internet, such as the check of the bundled yt-dlp release. |
| `-Pmcav.syncMeasurement=true` | Also measures how far the sound of a virtual machine drifts from its picture, with one JVM per test task and one test task at a time; run it on a quiet machine. |
| `./gradlew :<module>:pitest` | Runs [PIT](https://pitest.org/) mutation testing on one module, writing its report to `build/reports/pitest`. |
| `./gradlew :mcav-bukkit:buildMcv2Natives` | Builds the six MCV2 native libraries with Zig 0.16.0, which Gradle downloads and checksum-verifies, on a Gradle worker, so the module's tasks that do not need the libraries run meanwhile; it compiles as many objects at once as Gradle has workers. Resource processing runs this automatically; unchanged inputs are up to date. |
| `./gradlew :mcav-bukkit:formatMcv2Natives` | Formats the native sources with clang-format 18.1.8 from `requirements.lock`, checking its version first; `CLANG_FORMAT=/path/to/clang-format` names an installed one. `build` runs `checkMcv2NativesFormat`, which only checks the format. |
| `./gradlew :mcav-bukkit:pythonTest` | Runs the Python codec tests with pytest: the test files in parallel processes, then the timing tests alone. `check` runs it. |
| `./gradlew lockPythonRequirements` | Writes `requirements.lock` again from the Python versions of the catalog. |
| `./gradlew :mcav-common:generateDebianPins` | Writes `debian-vlc.json`, `debian-gtk2.json` and `debian-ffmpeg.json` in `mcav-common/src/main/resources/mcav/common/installers` again: it resolves the dependencies of VLC, GTK 2 and the system libraries of FFmpeg in the Debian bookworm and bookworm-security indexes of the latest snapshots on `snapshot.debian.org`, the newest version of each package winning, and pins each package to its snapshot URL and the SHA-256 of its index, dependencies first. Run it after a Debian point release or security update, then run the tests with `-Pmcav.networkTests=true`. |

The downloaded Zig compiler is cached in `mcav-bukkit/build/tools/zig/0.16.0`. Node.js 24.21.0, which runs the formatter
and builds the web page of `mcav-http`, is downloaded into `build/tools/node/24.21.0`. uv 0.12.23 is downloaded into
`build/tools/uv/0.12.23` and installs Python 3.13.16 and the locked packages into `build/tools/python/3.13.16`, the one
environment of the documentation, the Python codec tests and clang-format.

[CONTRIBUTING.md](https://github.com/PulseBeat02/mcav/blob/master/CONTRIBUTING.md) describes the tests, the coverage
lint, the property, fuzz and concurrency tests, mutation testing and the end-to-end test of the plugin in more detail.
