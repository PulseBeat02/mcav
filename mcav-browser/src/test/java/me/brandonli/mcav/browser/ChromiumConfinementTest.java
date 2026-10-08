/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package me.brandonli.mcav.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link ChromiumConfinement}: which paths a confined helper may read and change, and what it says it did.
 */
class ChromiumConfinementTest {

  @TempDir
  private Path folder;

  private HelperConfiguration configuration(final boolean confined, final Path serverFolder) {
    final Path session = this.folder.resolve("tmp").resolve("session");
    return new HelperConfiguration(
      new byte[HelperProtocol.TOKEN_BYTES],
      session.resolve("socket"),
      this.folder.resolve("natives"),
      session.resolve("profile"),
      URI.create("https://example.com/"),
      640,
      480,
      1,
      30,
      false,
      false,
      false,
      confined,
      serverFolder,
      List.of()
    );
  }

  /** Sorts the rules of the walk, whose order is the file system's; the readable and writable rules come last. */
  private static List<Landlock.Rule> walkSorted(final List<Landlock.Rule> rules, final int given) {
    final List<Landlock.Rule> sorted = new ArrayList<>(rules.subList(0, rules.size() - given));
    sorted.sort(Comparator.comparing(Landlock.Rule::path));
    sorted.addAll(rules.subList(rules.size() - given, rules.size()));
    return sorted;
  }

  @Test
  void everythingButTheHiddenFoldersMayBeReadAndOnlyTheWritableOnesChanged() throws IOException {
    final Path system = Files.createDirectories(this.folder.resolve("system"));
    final Path homes = Files.createDirectories(this.folder.resolve("home"));
    final Path home = Files.createDirectories(homes.resolve("user"));
    final Path otherHome = Files.createDirectories(homes.resolve("other"));
    final Path file = Files.writeString(this.folder.resolve("file.txt"), "file");
    // a link into a hidden folder is no way in
    Files.createSymbolicLink(this.folder.resolve("link"), home);
    final Path natives = home.resolve("natives");
    final Path session = this.folder.resolve("session");
    // the root itself is never hidden, and a folder that does not exist hides nothing
    final List<Path> hidden = List.of(home, this.folder, this.folder.resolve("missing"));
    final List<Landlock.Rule> rules = ChromiumConfinement.rules(this.folder, hidden, List.of(natives), List.of(session));
    assertEquals(
      List.of(
        new Landlock.Rule(file, false),
        new Landlock.Rule(otherHome, false),
        new Landlock.Rule(system, false),
        new Landlock.Rule(natives, false),
        new Landlock.Rule(session, true)
      ),
      walkSorted(rules, 2)
    );
  }

  @Test
  void aHiddenFolderIsFoundByItsRealPath() throws IOException {
    final Path real = Files.createDirectories(this.folder.resolve("real"));
    final Path kept = Files.createDirectories(this.folder.resolve("kept"));
    final Path alias = Files.createSymbolicLink(this.folder.resolve("alias"), real);
    final List<Landlock.Rule> rules = ChromiumConfinement.rules(this.folder, List.of(alias), List.of(), List.of());
    assertEquals(List.of(new Landlock.Rule(kept, false)), rules);
  }

  @Test
  @EnabledOnOs({ OS.LINUX, OS.MAC })
  void aFolderThatCannotBeListedGivesNothingBeneathIt() throws IOException {
    final Path closed = Files.createDirectories(this.folder.resolve("closed"));
    final Path hidden = Files.createDirectories(closed.resolve("hidden"));
    Files.createDirectories(closed.resolve("beside"));
    Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("-wx------"));
    try {
      assumeTrue(!Files.isReadable(closed), "the user may read every folder");
      assertEquals(List.of(), ChromiumConfinement.rules(this.folder, List.of(hidden), List.of(), List.of()));
    } finally {
      Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  void splitsListsOfPaths() {
    assertEquals(List.of(), ChromiumConfinement.pathsOf(null));
    final String separator = File.pathSeparator;
    assertEquals(List.of(Path.of("first"), Path.of("second")), ChromiumConfinement.pathsOf("first" + separator + separator + "second"));
  }

  @Test
  void findsTheJarsOfTheJavaAgents() {
    final List<String> arguments = List.of(
      "-Xmx512m",
      "-javaagent:/agents/coverage.jar=destfile=/out/helper.exec",
      "-javaagent:/agents/plain.jar"
    );
    assertEquals(List.of(Path.of("/agents/coverage.jar"), Path.of("/agents/plain.jar")), ChromiumConfinement.agentsOf(arguments));
  }

  @Test
  void findsTheFilesTheLibrariesOfTheSessionLinkTo() throws IOException {
    final Path installation = Files.createDirectories(this.folder.resolve("installation"));
    final Path library = Files.writeString(installation.resolve("libnss3.so"), "library");
    final Path linked = Files.createDirectories(this.folder.resolve("libraries"));
    Files.createSymbolicLink(linked.resolve("libnss3.so"), library);
    Files.createSymbolicLink(linked.resolve("relative.so"), Path.of("..", "installation", "libnss3.so"));
    Files.writeString(linked.resolve("plain.so"), "not a link");
    final List<Path> targets = ChromiumConfinement.linkedFiles(List.of(linked, this.folder.resolve("missing"), library));
    assertEquals(List.of(library, linked.resolve(Path.of("..", "installation", "libnss3.so"))), targets.stream().sorted().toList());
  }

  @Test
  void aHelperIsConfinedOnlyWhenItsOptionsAskOnLinuxWithLandlockAndOnMacOsWithSeatbelt() throws Exception {
    final Path server = this.folder.resolve("server");
    try (final SeatbeltLibrary library = SeatbeltLibrary.complete()) {
      final Seatbelt seatbelt = library.seatbelt();
      assertEquals(
        ChromiumConfinement.NOT_ASKED,
        ChromiumConfinement.confine(this.configuration(false, server), true, false, () -> 4, seatbelt)
      );
      assertEquals(
        ChromiumConfinement.NOT_ASKED,
        ChromiumConfinement.confine(this.configuration(false, server), false, true, () -> 4, seatbelt)
      );
      assertEquals(
        "Chromium runs without confinement: MCAV confines it on Linux and macOS only",
        ChromiumConfinement.confine(this.configuration(true, server), false, false, () -> 4, seatbelt)
      );
      assertEquals(
        "Chromium runs without confinement: this kernel has no Landlock, which Linux has since 5.13",
        ChromiumConfinement.confine(this.configuration(true, server), true, false, () -> -38, seatbelt)
      );
      assertEquals(List.of(), library.profiles(), "only macOS uses Seatbelt");
    }
    assertEquals(
      "Chromium runs without confinement: this system has no Seatbelt (sandbox_init)",
      ChromiumConfinement.confine(this.configuration(true, server), false, true, () -> 4, new Seatbelt(name -> Optional.empty()))
    );
  }

  @Test
  @DisabledOnOs(OS.MAC) // on macOS this would put this JVM in the sandbox
  void aSystemWithoutSeatbeltLeavesTheHelperAsItIsAndSaysWhy() throws IOException {
    final HelperConfiguration configuration = this.configuration(true, this.folder.resolve("server"));
    assertEquals(
      "Chromium runs without confinement: this system has no Seatbelt (sandbox_init)",
      ChromiumConfinement.confine(configuration, false, true)
    );
  }

  @Test
  void onMacOsTheWholeHelperIsConfinedWithAProfileOfTheRealPaths() throws Exception {
    final Path server = Files.createDirectories(this.folder.resolve("server"));
    final HelperConfiguration configuration = this.configuration(true, server);
    final Path session = Files.createDirectories(configuration.getSocket().getParent());
    final String said;
    final List<String> profiles;
    try (final SeatbeltLibrary library = SeatbeltLibrary.complete()) {
      said = ChromiumConfinement.confine(configuration, false, true, () -> -38, library.seatbelt());
      profiles = List.copyOf(library.profiles());
    }
    assertEquals(ChromiumConfinement.CONFINED, said);
    assertEquals(1, profiles.size());
    final String profile = profiles.getFirst();
    final String home = Path.of(System.getProperty("user.home")).toRealPath().toString();
    final String temporary = session.getParent().toRealPath().toString();
    assertTrue(
      profile.startsWith(
        "(version 1)\n(allow default)\n(deny file-read-data (subpath " +
          Seatbelt.quote(server.toRealPath().toString()) +
          ") (subpath " +
          Seatbelt.quote(home) +
          ") (subpath " +
          Seatbelt.quote(temporary) +
          "))\n(allow file-read-data (subpath " +
          Seatbelt.quote(Path.of(System.getProperty("java.home")).toRealPath().toString()) +
          ") (subpath " +
          Seatbelt.quote(this.folder.resolve("natives").toAbsolutePath().normalize().toString()) +
          ")"
      ),
      profile
    );
    assertTrue(
      profile.endsWith(
        "(deny file-write*)\n(allow file-write* (subpath " +
          Seatbelt.quote(session.toRealPath().toString()) +
          ") (subpath " +
          Seatbelt.quote(
            ChromiumConfinement.realPaths(List.of(Path.of("/dev")))
              .getFirst()
              .toString()
          ) +
          "))\n"
      ),
      profile
    );
    for (final Path entry : ChromiumConfinement.realPaths(ChromiumConfinement.pathsOf(System.getProperty("java.class.path")))) {
      assertTrue(profile.contains("(subpath " + Seatbelt.quote(entry.toString()) + ")"), () -> entry + " may be read");
    }
  }

  @Test
  void realPathsResolveLinksAndKeepMissingPathsAbsolute() throws IOException {
    final Path real = Files.createDirectories(this.folder.resolve("real"));
    final Path link = Files.createSymbolicLink(this.folder.resolve("link"), real);
    final Path missing = this.folder.resolve("missing").resolve("..").resolve("gone");
    assertEquals(
      List.of(real.toRealPath(), this.folder.resolve("gone").toAbsolutePath()),
      ChromiumConfinement.realPaths(List.of(link, missing))
    );
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aConfinedHelperCannotReadTheServersFolderOrTheTemporaryFolder() throws Exception {
    final int version = Landlock.version();
    assumeTrue(version >= 1, () -> Landlock.describeMissing(version));
    final Path server = Files.createDirectories(this.folder.resolve("server"));
    final Path config = Files.writeString(server.resolve("config.yml"), "token: secret");
    final Path otherSession = Files.createDirectories(this.folder.resolve("tmp").resolve("other-session"));
    final Path cookies = Files.writeString(otherSession.resolve("Cookies"), "cookie");
    final HelperConfiguration configuration = this.configuration(true, server);
    final Path session = Files.createDirectories(configuration.getSocket().getParent());
    final AtomicReference<String> said = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread thread = new Thread(() -> {
      try {
        said.set(ChromiumConfinement.confine(configuration, true, false));
        assertThrows(AccessDeniedException.class, () -> Files.readString(config, StandardCharsets.UTF_8));
        assertThrows(AccessDeniedException.class, () -> Files.readString(cookies, StandardCharsets.UTF_8));
        Files.writeString(session.resolve("profile.txt"), "profile");
        assertThrows(AccessDeniedException.class, () -> Files.writeString(this.folder.resolve("outside.txt"), "outside"));
      } catch (final Throwable thrown) {
        failure.set(thrown);
      }
    }, "confinement-test");
    thread.start();
    thread.join(TimeUnit.SECONDS.toMillis(60));
    assertFalse(thread.isAlive());
    assertNull(failure.get(), () -> "the confined thread failed: " + failure.get());
    assertEquals(ChromiumConfinement.CONFINED, said.get());
    assertEquals("profile", Files.readString(session.resolve("profile.txt"), StandardCharsets.UTF_8));
    assertTrue(Files.isReadable(config), "the other threads are free");
  }

  @Test
  @EnabledOnOs(OS.MAC)
  void onMacOsAConfinedHelperCannotReadTheServersFolderOrTheTemporaryFolderAndNeitherCanWhatItStarts() throws Exception {
    final Path server = Files.createDirectories(this.folder.resolve("server"));
    final Path config = Files.writeString(server.resolve("config.yml"), "token: secret");
    final Path otherSession = Files.createDirectories(this.folder.resolve("tmp").resolve("other-session"));
    final Path cookies = Files.writeString(otherSession.resolve("Cookies"), "cookie");
    final HelperConfiguration configuration = this.configuration(true, server);
    final Path session = Files.createDirectories(configuration.getSocket().getParent());
    final Path outside = this.folder.resolve("outside.txt");
    // Seatbelt holds for a whole process and cannot be lifted, so a JVM of its own confines itself
    final Process probe = new ProcessBuilder(
      Path.of(System.getProperty("java.home"), "bin", "java").toString(),
      "--enable-native-access=ALL-UNNAMED",
      "-cp",
      System.getProperty("java.class.path"),
      SeatbeltProbeMain.class.getName(),
      server.toString(),
      configuration.getSocket().toString(),
      configuration.getNatives().toString(),
      config.toString(),
      cookies.toString(),
      outside.toString()
    )
      .redirectErrorStream(true)
      .start();
    final String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(probe.waitFor(60, TimeUnit.SECONDS), output);
    assertEquals(0, probe.exitValue(), output);
    assertEquals(
      List.of(
        "said " + ChromiumConfinement.CONFINED,
        "server folder: denied",
        "other session: denied",
        "session: written",
        "outside: denied",
        "started cat: refused",
        "sandboxed: true"
      ),
      output
        .lines()
        .filter(line -> !line.startsWith("WARNING"))
        .toList(),
      output
    );
    assertEquals("profile", Files.readString(session.resolve("profile.txt"), StandardCharsets.UTF_8));
    assertFalse(Files.exists(outside));
    assertTrue(Files.isReadable(config), "this process is free");
  }
}
