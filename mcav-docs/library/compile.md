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

- Profit! If you are looking for the plugin jar, it will be located in the `/mcav-plugin/build/libs` directory. Use the jar
with the "-all" suffix for the plugin.

## Build Options

Gradle runs on any JDK 17 or newer. The build needs a Java 25 toolchain, which Gradle downloads if the machine has none,
and it downloads a Node.js of its own for the code formatter and the web page of `mcav-http`. No credentials are
needed, except to publish. The project builds on any one of Windows, macOS or Linux: tests that need another operating
system, or a program the machine lacks (VLC, QEMU, a display), skip themselves. `build` also enforces the coverage lint,
and the code those skipped tests would have run shows up there as uncovered lines, so on such a machine build with
`-Pmcav.coverage=false`. Every compilation runs
[Error Prone](https://errorprone.info/) and, for production code, the Checker Framework, and it must stay free of
warnings.

| Option | What it does |
|--------|---------------|
| `./gradlew build` | Compiles everything, checks formatting, runs the tests, the property tests and a replay of every fuzz input, and enforces the coverage lint and the source lints (`qualifiedNames`, `logMessages`, `variableNames`). |
| `./gradlew propertyTest`, `fuzzTest` | Runs only the property tests, or only the fuzz tests; `-Pfuzz.seconds=<n>` fuzzes each fuzz test for that long. |
| `./gradlew jcstressTest` | Runs the concurrency tests of `mcav-jcstress` on jcstress. |
| `./gradlew spotlessApply` | Formats the sources; run before building after editing code. |
| `-Pmcav.testJavaHome=<path to a Java 25 JDK>` | Runs the tests on another JDK, for machines whose JDK cannot load the bundled OpenCV natives. |
| `./gradlew coverageLint` | Prints every line and branch no test covers, as `file:line`. |
| `-Pmcav.coverage=false` | Turns off the coverage lint, which `check`, and so `build`, enforces by default; any other value keeps it on. |
| `-Pmcav.networkTests=true` | Also runs the tests that download from the internet, such as the check of the bundled yt-dlp release. |
| `-Pmcav.syncMeasurement=true` | Also measures how far the sound of a virtual machine drifts from its picture; run it on a quiet machine. |
| `./gradlew :<module>:pitest` | Runs [PIT](https://pitest.org/) mutation testing on one module, writing its report to `build/reports/pitest`. |
| `./gradlew :mcav-bukkit:buildMcv2Natives -Pmcav.natives=build` | Rebuilds the six native libraries of the MCV2 encoder with Zig 0.16.0; the normal build uses the committed ones. |

[CONTRIBUTING.md](https://github.com/PulseBeat02/mcav/blob/master/CONTRIBUTING.md) describes the tests, the coverage
lint, the property, fuzz and concurrency tests, mutation testing and the end-to-end test of the plugin in more detail.
