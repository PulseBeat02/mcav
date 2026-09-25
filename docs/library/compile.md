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

- Profit! If you are looking for the plugin jar, it will be located in the `/sandbox/plugin/build/libs` directory. Use the jar
with the "-all" suffix for the plugin.

## Build Options

The build needs a Java 25 toolchain, which Gradle downloads if the machine has none, and it downloads a Node.js of its
own for the code formatter. Every compilation runs [Error Prone](https://errorprone.info/) and, for production code,
the Checker Framework, and it must stay free of warnings.

| Option | What it does |
|--------|---------------|
| `./gradlew build` | Compiles everything, checks formatting, and runs the tests. |
| `./gradlew spotlessApply` | Formats the sources; run before building after editing code. |
| `-Pmcav.testJavaHome=<path to a Java 25 JDK>` | Runs the tests on another JDK, for machines whose JDK cannot load the bundled OpenCV natives. |
| `./gradlew coverageLint` | Prints every line and branch no test covers, as `file:line`. |
| `-Pmcav.coverage` | Makes `check` enforce the coverage lint as well. |
| `-Pmcav.networkTests=true` | Also runs the tests that download from the internet, such as the check of the bundled yt-dlp release. |
| `-Pmcav.syncMeasurement=true` | Also measures how far the sound of a virtual machine drifts from its picture; run it on a quiet machine. |
| `./gradlew :<module>:pitest` | Runs [PIT](https://pitest.org/) mutation testing on one module, writing its report to `build/reports/pitest`. |

[CONTRIBUTING.md](https://github.com/PulseBeat02/mcav/blob/master/CONTRIBUTING.md) describes the tests, the coverage
lint and the end-to-end test of the plugin in more detail.
