# Contributions to the MCAV Project

I welcome all contributors to the MCAV project. Contributing is incredibly easy. In general, I require all contributions
to follow the following guidelines:

- Appropriate (is it reasonable, necessary?)
- Code quality (follow OOP, avoid DRY code, etc.)
- Code formatting (run `./gradlew spotlessApply`, which adds the license header and formats the code with prettier-java)

## Tests

Every module stays at 100% line and branch coverage on a machine where every test can run, and tests check the behavior
the code should have, not whatever it happens to do. When a test fails, fix the code if the code is wrong.

- `./gradlew coverageLint` runs the tests of every module and prints each line or branch that no test covers as
  `file:line`, failing the build when there is any. Run `./gradlew :<module>:coverageLint` for a single module.
- `./gradlew check -Pmcav.coverage` makes `check` enforce the lint as well. Without the property, `check` does not,
  because some tests skip themselves on machines without VLC, QEMU or a display, or whose OpenCV build cannot read
  video files (the bundled Linux build cannot), and the code they test would show up as gaps there.
- A line that no test can run, such as a constructor only a Minecraft server may call, goes into
  `coverage-exceptions.txt` next to the build file of its module, together with the reason. The lint fails when an
  entry is no longer needed, so the list cannot hide new gaps.
- Some JDK builds cannot load the bundled OpenCV natives. Pass `-Pmcav.testJavaHome=<path to a Java 25 JDK>` to run
  the tests on another JDK.
- Tests that download from the internet, such as the check of the bundled yt-dlp release against the checksums
  published on GitHub and a real installation of yt-dlp, only run with `-Pmcav.networkTests=true`.
- The tests of `mcav-browser` that start a real browser download the CEF build of the machine on their first run,
  as a server does (136–165 MB, into `~/.mcav/cache/jcef`); on Linux they need Xvfb.
- The measurement of how far the sound of a virtual machine drifts from its picture times real events, which a busy
  machine delays, so it only runs with `-Pmcav.syncMeasurement=true`, on a quiet machine.

## Static Analysis

Every compilation runs [Error Prone](https://errorprone.info/) with its default checks, on production and test code,
next to the Checker Framework's nullness checker on production code. The build must compile without a single warning:
fix what Error Prone reports instead of suppressing it.

## Mutation Testing

`./gradlew :<module>:pitest` runs [PIT](https://pitest.org/) on a module: it changes the compiled code in small ways,
such as flipping a condition, and reruns the tests to see whether one of them notices. The report is written to
`build/reports/pitest` of the module. PIT is not part of `check`, because it reruns the tests many times; it uses the
same JVM options as the tests of the module and honors `-Pmcav.testJavaHome`.

## End-to-End Test

`./gradlew :sandbox:plugin:e2eTest -Pmcav.e2e=true -Pmcav.acceptMinecraftEula=true` runs the sandbox plugin on a real,
headless Paper 26.2 server together with Simple Voice Chat, exactly as on a production server:

- The modules of this build are published into `build/e2e-repository`, and the server downloads them from there
  instead of the published snapshots, together with every other library of the plugin, the JavaCV natives, VLC and
  yt-dlp. The test needs the internet, and the first run takes a few minutes.
- Paper and Simple Voice Chat are downloaded once into `sandbox/plugin/build/e2e-cache` and checked against their
  SHA-256 and SHA-512 hashes.
- The server runs without a display, with the audio web page and Simple Voice Chat audio turned on, on free ports.
  The test runs console commands, requests the media information from the web page, stops the server and fails on
  any error in the server log.
- Running a Minecraft server means accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA), which is what
  `-Pmcav.acceptMinecraftEula=true` does; without it, the test is skipped.
