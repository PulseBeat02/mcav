# Contributions to the MCAV Project

I welcome all contributors to the MCAV project. Contributing is incredibly easy. In general, I require all contributions
to follow the following guidelines:

- Appropriate (is it reasonable, necessary?)
- Code quality (follow OOP, avoid DRY code, etc.)
- Code formatting (run `./gradlew spotlessApply`, which adds the license header and formats the code with prettier-java)

## Documentation

The documentation is the Jupyter Book in `mcav-docs`. `./gradlew :mcav-docs:build` builds it into
`mcav-docs/build/html` (open `index.html`), and `./gradlew build` builds it too. Gradle downloads uv, checks it against
the SHA-256 in `buildSrc/src/main/resources/uv-checksums.properties`, and with it installs the Python of
`gradle/libs.versions.toml` and the packages of `mcav-docs/requirements.lock`, each checked against its hash, all into
`mcav-docs/build`. Set `UV=/path/to/uv` to use an installed uv of the same version instead. Jupyter Book runs with
`--warningiserror`, so a Sphinx warning fails the build. Downloading uv and Python needs GitHub, and the packages need
PyPI.

To change a documentation package or the Python, change its version in `gradle/libs.versions.toml`, run
`./gradlew :mcav-docs:lockDocsRequirements`, which writes `mcav-docs/requirements.lock` with every package pinned and
hashed, and commit both; the build fails while the lock pins other versions than the catalog. Read the Docs installs
the same lock through `mcav-docs/requirements.txt`, so the Python of `.readthedocs.yaml` stays at the minor version of
`docs-python`. To update uv, copy the SHA-256 of each archive from the `.sha256` files of its GitHub release into
`uv-checksums.properties`.

## Tests

Every module stays at 100% line and branch coverage on a machine where every test can run, and tests check the behavior
the code should have, not whatever it happens to do. When a test fails, fix the code if the code is wrong.

- `./gradlew coverageLint` runs the tests of every module and prints each line or branch that no test covers as
  `file:line`, failing the build when there is any. Run `./gradlew :<module>:coverageLint` for a single module.
- `check`, and so `build`, enforces the lint by default. Some tests skip themselves on machines without VLC, Chrome,
  QEMU, a display or a sound device, and the code they test would show up as gaps there, so on such a machine pass
  `-Pmcav.coverage=false`, which turns the gate off; any other value keeps it on.
- A line that no test can run, such as a constructor only a Minecraft server may call, goes into
  `coverage-exceptions.txt` next to the build file of its module, together with the reason. The lint fails when an
  entry is no longer needed, so the list cannot hide new gaps.
- Some JDK builds cannot load the bundled OpenCV natives. Pass `-Pmcav.testJavaHome=<path to a Java 25 JDK>` to run
  the tests on another JDK.
- Tests that download from the internet, such as the check of the bundled yt-dlp release against the checksums
  published on GitHub and a real installation of yt-dlp, only run with `-Pmcav.networkTests=true`.
- The tests of `mcav-browser` that start a real browser download the CEF build of the machine on their first run,
  as a server does (136–165 MB, into `~/.mcav/cache/jcef`), and on Linux the libraries the machine lacks (about
  13 MB, into `~/.mcav/cache/jcef-libraries`); they need no X server.
- The measurements of how far the sound of a virtual machine or of a browser page drifts from its picture time real
  events, which a busy machine delays, so they only run with `-Pmcav.syncMeasurement=true`, on a quiet machine.

## Property, Fuzz and Concurrency Tests

Property tests are [jqwik](https://jqwik.net/) properties in classes named `*PropertyTest`, and fuzz tests are
[Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) fuzz tests in classes named `*FuzzTest`, both in the test
source set of their module. `./gradlew check`, and so `./gradlew build`, runs both, next to the ordinary tests.

- `./gradlew propertyTest` runs the properties of every module with 200 tries each (`-Pproperty.tries=<n>`); `test`
  runs them too, with 10 tries (`-Pproperty.smokeTries=<n>`). Every property pins its seed, so a run is reproducible
  and a failure reports the seed and the shrunk sample.
- `./gradlew fuzzTest` replays the committed inputs of every fuzz test, the seed corpus and every crash reproducer in
  `src/test/resources/<package>/<class>Inputs/<method>`. With `-Pfuzz.seconds=<n>`, each fuzz test is also fuzzed
  for that many seconds, one JVM per test class; a crash is written to `build/jazzer` of the module. Commit the input
  of a crash you fixed next to the others, so every build replays it.
- `./gradlew jcstressTest` runs the concurrency tests of `mcav-jcstress` on OpenJDK's
  [jcstress](https://github.com/openjdk/jcstress) harness, in its quick mode (`-Pjcstress.mode=quick|default|stress`,
  `-Pjcstress.timeBudgetMinutes=<n>`, `-Pjcstress.tests=<regex>`, `-Pjcstress.cpus=<n>`). The module is a test
  harness: it is not published and is kept out of the coverage lint and of PIT. A result a test marks as forbidden
  fails the task.

## The MCV2 Native Libraries

`mcav-bukkit` builds the MCV2 encoder's six native libraries (Linux, Windows and macOS on x86-64 and ARM64) from
`src/main/native/mcv2` whenever resources are processed. Gradle downloads [Zig](https://ziglang.org/) 0.16.0 and checks
its pinned SHA-256; no C/C++ toolchain needs to be installed. `ZIG=/path/to/zig` overrides the download with that version.
`./gradlew :mcav-bukkit:buildMcv2Natives` runs the cacheable task directly; unchanged inputs are up to date. Generated
libraries and their `SHA256SUMS` and `SOURCES` manifests live under `mcav-bukkit/build/generated/natives/mcav/mcv2/natives`.
Commit only the sources. The loader checks the generated digests, and the tests compare the source manifest with the
tree. `./gradlew :mcav-bukkit:formatMcv2Natives` formats the sources with clang-format (`CLANG_FORMAT=/path/to/clang-format`).
The formatter is separate from `build` and needs clang-format 18.1.8.

MCV2's Java tools live in `mcav-bukkit/src/test/java/me/brandonli/mcav/bukkit/media/mcv2/Mcv2Tools.java`.
Its Python tools and independent reference decoder live in `mcav-bukkit/src/test/python`. Their tests run separately
with `python3 -m unittest discover -s mcav-bukkit/src/test/python`, using Python 3.12 or newer with numpy, Pillow,
moderngl and matplotlib. The [MCV2 article](mcav-docs/mcv2.md#reproducing-the-measurements-and-figures) lists the tools and their commands.

## Static Analysis

Every compilation runs [Error Prone](https://errorprone.info/) with its default checks, on production and test code,
next to the Checker Framework's nullness checker on production code. The build must compile without a single warning:
fix what Error Prone reports instead of suppressing it. Error Prone also fails the build on the code-quality rules of
the project that it can check: the most restrictive modifiers (a field that is never reassigned is `final`, a private
method that uses no instance state is `static`, a utility class has a private constructor), no unused code, constants in
UPPER_SNAKE_CASE, one variable per declaration, overloads next to each other and no wildcard imports; the list is in
`buildSrc/src/main/kotlin/mcav.java-library.gradle.kts`.

Three lints of the sources run in `check`. Java code imports the types it names: `qualifiedNames` fails on a fully
qualified type name, except on a line marked `// fqn: <why>` where two types of one simple name meet in a file. Log
messages live in constants: `logMessages` fails on a log call whose message does not name a constant (an
UPPER_SNAKE_CASE field such as `FAILED_TO_START`, a `private static final String` next to the class's logger) or that
joins strings with `+`; the message's SLF4J placeholders take the other arguments. Names say what they hold:
`variableNames` fails on a variable, parameter or field named by one letter or by a short form a word says better
(`buf`, `tmp`, `idx`...); generic type parameters stay single capitals.

## Mutation Testing

`./gradlew :<module>:pitest` runs [PIT](https://pitest.org/) on a module: it changes the compiled code in small ways,
such as flipping a condition, and reruns the tests to see whether one of them notices. The report is written to
`build/reports/pitest` of the module. PIT is not part of `check`, because it reruns the tests many times; it uses the
same JVM options as the tests of the module and honors `-Pmcav.testJavaHome`.

## End-to-End Test

`./gradlew :mcav-plugin:e2eTest -Pmcav.e2e=true -Pmcav.acceptMinecraftEula=true` runs the MCAV plugin on a real,
headless Paper 26.3 server together with Simple Voice Chat, exactly as on a production server:

- The modules of this build are published into `build/e2e-repository`, and the server downloads them from there
  instead of the published snapshots, together with every other library of the plugin, the JavaCV natives, VLC and
  yt-dlp. The test needs the internet, and the first run takes a few minutes.
- Paper and Simple Voice Chat are downloaded once into `mcav-plugin/build/e2e-cache` and checked against their
  SHA-256 and SHA-512 hashes.
- The server runs without a display, with the audio web page and Simple Voice Chat audio turned on, on free ports.
  The test runs console commands, requests the media information from the web page, stops the server and fails on
  any error in the server log.
- Running a Minecraft server means accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA), which is what
  `-Pmcav.acceptMinecraftEula=true` does; without it, the test is skipped.
