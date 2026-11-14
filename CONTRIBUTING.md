# Contributions to the MCAV Project

MCAV welcomes all contributors, and contributing is easy. Make sure your contribution follows these guidelines:

- Appropriate (is it reasonable, necessary?)
- Code quality (follow OOP and keep code DRY: extract a utility on its second use, not the first)
- Code formatting (run `./gradlew spotlessApply`, which adds the license header and formats the code with prettier-java)

`./gradlew spotlessRepositoryCheck` checks whitespace in the repository text files, including
`gradle/libs.versions.toml`, `buildSrc/src/main/resources/tool-checksums.properties` and `requirements.lock`.

Versions live in `gradle/libs.versions.toml`; only the toolchain resolver's stays in `settings.gradle.kts`, whose
`plugins` block cannot read the catalog. `buildSrc`'s `BuildFilesTest` (`./gradlew :buildSrc:test`) checks the other
rules of the build files: a module's `build.gradle.kts` only applies `mcav.*` conventions and lists its dependencies,
the modules in `settings.gradle.kts` and each section of the catalog keep their entries sorted by name, every Kotlin
source of `buildSrc` starts with the license header of `HEADER` and no build script carries it, and no line is longer
than 140 columns.

## Documentation

The documentation is the Jupyter Book in `mcav-docs`. `./gradlew :mcav-docs:build` builds it into `mcav-docs/build/html`
(open `index.html`), and `./gradlew build` builds it too. Gradle downloads uv, checks it against the SHA-256 in
`buildSrc/src/main/resources/tool-checksums.properties`, and with it installs the Python of `gradle/libs.versions.toml`
and the packages of `requirements.lock`, each checked against its hash, all into `build/tools`. The documentation, the
Python codec tests of `mcav-bukkit` and clang-format use this one environment. Set `UV=/path/to/uv` to use an installed
uv instead. Both `installPythonEnvironment` and `lockPythonRequirements` reject an executable reporting a version other
than the catalog's uv 0.12.23. Jupyter Book runs with `--warningiserror`, so a Sphinx warning fails the build.
Downloading uv and Python needs GitHub, and the packages need PyPI.

To change a Python package or the Python, change its version in `gradle/libs.versions.toml`, run
`./gradlew lockPythonRequirements`, which writes `requirements.lock` with every package pinned and hashed, and commit
it; the build fails while the lock pins other versions than the catalog. Read the Docs installs the lock through
`mcav-docs/requirements.txt`. Local builds use `python` 3.13.16; `.readthedocs.yaml` selects the minor version 3.13. A
`buildSrc` unit test enforces that minor match, without asserting which patch version the provider installs. To update
uv or Zig, put the SHA-256 of each of its six archives into `buildSrc/src/main/resources/tool-checksums.properties`,
under `<tool>/<version>/<archive>`: uv's are in the `.sha256` files of its GitHub release, Zig's in
`https://ziglang.org/download/index.json`. A `buildSrc` unit test checks that the file pins the six archives of the
catalog's versions and nothing else.

`mcav-docs/_config.yml` is the authored book configuration. Jupyter Book generates `conf.py` at
`mcav-docs/build/tmp/buildDocs/book/conf.py` locally; Read the Docs runs `jupyter-book config sphinx mcav-docs/` before
reading `mcav-docs/conf.py`. Do not edit or commit either generated file.

## Tests

The twelve Java modules using `buildSrc/src/main/kotlin/mcav.module.gradle.kts` are `mcav-browser`, `mcav-bukkit`,
`mcav-common`, `mcav-discord`, `mcav-http`, `mcav-installer`, `mcav-lwjgl`, `mcav-mod`, `mcav-plugin`, `mcav-vm`,
`mcav-vnc` and `mcav-voicechat`. They must stay at 100% line and branch coverage on a machine where every test can run,
and tests check the behavior the code should have, not whatever it happens to do. When a test fails, fix the code if the
code is wrong. Coverage, property, fuzz and PIT commands below apply to these twelve modules; `<module>` means one of
their names.

`mcav-docs` builds the book, and `buildSrc` has separate bootstrap tests (`./gradlew :buildSrc:test`). `mcav-jcstress`
uses the Java-library, formatting and lint conventions through `buildSrc/src/main/kotlin/mcav.jcstress.gradle.kts`; it
skips Checker Framework and removes `-Werror` from `compileJava`. It has no module coverage, property, fuzz or PIT
tasks.

- `./gradlew coverageLint` runs the tests of these twelve modules and prints each line or branch that no test covers as
  `file:line`, failing the build when there is any. Run `./gradlew :<module>:coverageLint` for a single module.
- `check`, and so `build`, enforces the lint by default. Some tests skip themselves on machines without VLC, QEMU, a
  display or a sound device, and the code they test would show up as gaps there, so on such a machine pass
  `-Pmcav.coverage=false`, which turns the gate off; any other value keeps it on.
- A line that no test can run, such as a constructor only a Minecraft server may call, goes into
  `coverage-exceptions.txt` next to the build file of its module, together with the reason. The lint fails when an entry
  is no longer needed, so the list cannot hide new gaps.
- Some JDK builds cannot load the bundled OpenCV natives. Pass `-Pmcav.test_java_home=<path to a Java 25 JDK>` to run
  the tests on another JDK.
- Tests that download from the internet, such as the check of the bundled yt-dlp release against the checksums published
  on GitHub and a real installation of yt-dlp, only run with `-Pmcav.networkTests=true`.
- The tests of `mcav-browser` that start a real browser download the CEF build of the machine on their first run, as a
  server does (136–165 MB, into `~/.mcav/cache/jcef`), and on Linux the libraries the machine lacks (about 13 MB, into
  `~/.mcav/cache/jcef-libraries`); they need no X server.
- The measurements of how far the sound of a virtual machine or of a browser page drifts from its picture time real
  events, which a busy machine delays, so they only run with `-Pmcav.sync_measurement=true`, on a quiet machine; then
  each test task runs in one JVM, and no two test tasks run at the same time.
- `test` spreads the test classes of a module over as many JVMs as half the processors of the machine, at least one;
  each JVM runs its tests one at a time. The JVMs run at the same time, so a test writes outside its temporary folders
  only through a file lock or an atomic move, and no two test classes bind the same fixed port.

## Property, fuzz and concurrency tests

Property tests are [jqwik](https://jqwik.net/) properties in classes named `*PropertyTest`, and fuzz tests are
[Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) fuzz tests in classes named `*FuzzTest`, both in the test
source set of their module. `./gradlew check`, and so `./gradlew build`, runs both, next to the ordinary tests.

- `./gradlew propertyTest` runs the properties of these twelve modules with 200 tries each (`-Pproperty.tries=<n>`);
  `test` runs them too, with 10 tries (`-Pproperty.smoke_tries=<n>`). Every property pins its seed, so a run is
  reproducible and a failure reports the seed and the shrunk sample. `propertyTest` keeps jqwik's run database in
  `build/jqwik-database-propertyTest` of the module; `test` keeps none, as its JVMs would overwrite one another's.
- `./gradlew fuzzTest` replays the committed inputs of every fuzz test, the seed corpus and every crash reproducer in
  `src/test/resources/<package>/<class>Inputs/<method>`. With `-Pfuzz.seconds=<n>`, Jazzer also fuzzes each fuzz test
  for that many seconds, one JVM per test class, and writes a crash to `build/jazzer` of the module. Commit the input of
  a crash you fixed next to the others, so every build replays it.
- `./gradlew jcstressTest` runs the concurrency tests of `mcav-jcstress` on OpenJDK's
  [jcstress](https://github.com/openjdk/jcstress) harness, in its quick mode (`-Pjcstress.mode=quick|default|stress`,
  `-Pjcstress.timeBudgetMinutes=<n>`, `-Pjcstress.tests=<regex>`, `-Pjcstress.cpus=<n>`). The module is a test harness:
  the build does not publish it and keeps it out of the coverage lint and of PIT. A result a test marks as forbidden
  fails the task.

## The MCV2 native libraries

`mcav-bukkit` builds the MCV2 encoder's six native libraries (Linux, Windows and macOS on x86-64 and ARM64) from
`src/main/native/mcv2` whenever Gradle processes resources. Gradle downloads [Zig](https://ziglang.org/) 0.16.0 and
checks its pinned SHA-256; you need no installed C/C++ toolchain. `ZIG=/path/to/zig` overrides the download with that
version. The task hands the compiles and links to a Gradle worker, so the tasks of `mcav-bukkit` that do not need the
libraries run meanwhile; it compiles the 22 objects, then links the six libraries, as many at once as Gradle has workers
(`--max-workers`). Gradle caches the downloaded compiler in `mcav-bukkit/build/tools/zig/0.16.0`.
`./gradlew :mcav-bukkit:buildMcv2Natives` runs the cacheable task directly; unchanged inputs are up to date. Generated
libraries and their `SHA256SUMS` and `SOURCES` manifests live under
`mcav-bukkit/build/generated/natives/mcav/mcv2/natives`. Commit only the sources. The loader checks the generated
digests, and the tests compare the source manifest with the tree. `./gradlew :mcav-bukkit:formatMcv2Natives` formats the
sources with clang-format 18.1.8 from `requirements.lock`; `build` runs `checkMcv2NativesFormat`, which fails on an
unformatted line and changes nothing. `CLANG_FORMAT=/path/to/clang-format` names an installed clang-format instead;
Windows on ARM64 needs it, as no clang-format 18.1.8 package exists for it. Both tasks check the reported version first.

MCV2's Java tools live in `mcav-bukkit/src/test/java/me/brandonli/mcav/bukkit/media/mcv2/Mcv2Tools.java`. Its Python
tools and independent reference decoder live in `mcav-bukkit/src/test/python`. `check` runs their tests with
`./gradlew :mcav-bukkit:pythonTest`: pytest runs the test files in parallel processes, no more than Gradle's workers,
and then `test_shader_timing.py` alone, on the Python and packages of `requirements.lock`. The JUnit reports are in
`mcav-bukkit/build/test-results/pythonTest`. The [MCV2
article](https://mcav.readthedocs.io/en/latest/mcv2.html#reproducing-the-measurements-and-figures) lists the tools and
their commands.

## Static analysis

The Java-library convention runs [Error Prone](https://errorprone.info/) with its default checks on production and test
code, next to the Checker Framework's nullness checker on production code. The twelve module builds must compile without
a single warning (the section on tests describes the isolated jcstress exceptions): fix what Error Prone reports instead
of suppressing it. Error Prone also fails the build on the code-quality rules of the project that it can check: the most
restrictive modifiers (a field that is never reassigned is `final`, a utility class has a private constructor), no
unused code, constants in UPPER_SNAKE_CASE, one variable per declaration, overloads next to each other and no wildcard
imports; the list is in `buildSrc/src/main/kotlin/mcav.java-library.gradle.kts`.

Four lints of the sources run in `check`. Java code imports the types it names: `qualifiedNames` fails on a fully
qualified type name, except on a line marked `// fqn: <why>` where two types of one simple name meet in a file. Log
calls never build their message by concatenation: `logMessages` fails on a log call whose arguments join strings with
`+`, such as `LOGGER.warn("Could not reach " + host)`. Write the template inline and let its SLF4J placeholders take the
other arguments: `LOGGER.warn("Could not reach {}, retrying.", host)`. Names say what they hold: `variableNames` fails
on a variable, parameter or field named by one letter or by a short form a word says better (`buf`, `tmp`, `idx`...);
type parameters are a word plus `Type`, such as `<KeyType>`. A comment longer than one line is one `/* ... */` block:
`commentForm` fails on two or more lines in a row that hold only a `//` comment, except `// fqn:` markers and tool
directives such as `// spotless:off`.

## Mutation testing

`./gradlew :<module>:pitest` runs [PIT](https://pitest.org/) on a module: it changes the compiled code in small ways,
such as flipping a condition, and reruns the tests to see whether one of them notices. PIT writes the report to
`build/reports/pitest` of the module. PIT is not part of `check`, because it reruns the tests many times; it uses the
ordinary `test` task's JVM options and system properties once, including `mcav.networkTests`, `mcav.sync_measurement`,
`jqwik.database`, `jqwik.failures.after.default` and the ordinary smoke tries (`jqwik.tries.default`). It honors
`-Pmcav.test_java_home`; the property and fuzz tasks keep their separate roles and budgets. PIT's minions, the JVMs that
run your tests against each mutant, use only the C1 compiler (`-XX:TieredStopAtLevel=1`), because a higher tier can keep
running a method's original code after PIT swaps a mutant in, and the mutant then looks like a survivor.

## End-to-end test

`./gradlew :mcav-plugin:e2eTest -Pmcav.e2e=true -Pmcav.acceptMinecraftEula=true` runs the MCAV plugin on a real,
headless Paper 26.3 server together with Simple Voice Chat, exactly as on a production server:

- The test publishes the modules of this build into `build/e2e-repository`, and the server downloads them from there
  instead of the published snapshots, together with every other library of the plugin, the JavaCV natives, VLC and
  yt-dlp. The test needs the internet, and the first run takes a few minutes.
- The test downloads Paper and Simple Voice Chat once into `mcav-plugin/build/e2e-cache` and checks them against their
  SHA-256 and SHA-512 hashes.
- The server runs without a display, with the audio web page and Simple Voice Chat audio turned on, on free ports. The
  test runs console commands, requests the media information from the web page, stops the server and fails on any error
  in the server log.
- Running a Minecraft server means accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA), which is what
  `-Pmcav.acceptMinecraftEula=true` does; without it, the test skips itself.

## Publishing

`./gradlew publishLibraries` publishes the ten library modules, `mcav-browser`, `mcav-bukkit`, `mcav-common`,
`mcav-discord`, `mcav-http`, `mcav-installer`, `mcav-lwjgl`, `mcav-vm`, `mcav-vnc` and `mcav-voicechat`, as
`me.brandonli:<module>:1.0.0-SNAPSHOT` to the snapshot repository `https://repo.brandonli.me/snapshots`, and into
`build/e2e-repository`. It needs the credentials of that repository as the Gradle properties `brandonliUsername` and
`brandonliPassword`, for example in `~/.gradle/gradle.properties`. It does not publish the plugin, the mod, the
documentation or `mcav-jcstress`.

Library jars retain unversioned filenames, such as `mcav-bukkit/build/libs/mcav-bukkit.jar`; publication coordinates use
the root project's release base. The plugin derives `1.0.0-v26.3` from that base and the Minecraft version.
