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

class ChromiumConfinementTest {

  private static final String NO_SEATBELT_REASON = "Because this system has no Seatbelt (sandbox_init), Chromium runs without confinement.";
  private static final String MISSING_LANDLOCK_MESSAGE = "%s, so this kernel cannot restrict!";

  private static final String CONFINED_THREAD_FAILURE_PREFIX = "the confined thread failed: ";
  private static final String SESSION_FOLDER = "session";
  private static final String TEMPORARY_FOLDER = "tmp";
  private static final String SOCKET_FILE = "socket";
  private static final String NATIVES_FOLDER = "natives";
  private static final String PROFILE_FOLDER = "profile";
  private static final String SYSTEM_FOLDER = "system";
  private static final String HOMES_FOLDER = "home";
  private static final String USER_FOLDER = "user";
  private static final String OTHER_HOME_FOLDER = "other";
  private static final String PLAIN_FILE = "file.txt";
  private static final String LINK_NAME = "link";
  private static final String REAL_FOLDER = "real";
  private static final String KEPT_FOLDER = "kept";
  private static final String ALIAS_LINK = "alias";
  private static final String CLOSED_FOLDER = "closed";
  private static final String HIDDEN_FOLDER = "hidden";
  private static final String BESIDE_FOLDER = "beside";
  private static final String FIRST_PATH = "first";
  private static final String SECOND_PATH = "second";
  private static final String COVERAGE_AGENT_FILE = "/agents/coverage.jar";
  private static final String PLAIN_AGENT_FILE = "/agents/plain.jar";
  private static final String INSTALLATION_FOLDER = "installation";
  private static final String NSS_LIBRARY_FILE = "libnss3.so";
  private static final String LIBRARIES_FOLDER = "libraries";
  private static final String RELATIVE_LIBRARY_FILE = "relative.so";
  private static final String PLAIN_LIBRARY_FILE = "plain.so";
  private static final String PARENT_FOLDER = "..";
  private static final String GONE_FOLDER = "gone";
  private static final String SERVER_CONFIGURATION_FILE = "config.yml";
  private static final String COOKIES_FILE = "Cookies";
  private static final String PROFILE_TEXT_FILE = "profile.txt";
  private static final String OUTSIDE_FILE = "outside.txt";
  private static final String JAVA_BIN_FOLDER = "bin";
  private static final String JAVA_EXECUTABLE = "java";
  private static final String OTHER_SESSION_FOLDER = "other-session";
  private static final String CONFINEMENT_THREAD_NAME = "confinement-test";

  private static final int MISSING_SYSTEM_CALL_ERROR = -38;

  private static final String SERVER_CONFIGURATION_TEXT = "token: secret";
  private static final String COOKIE_TEXT = "cookie";

  private static final int JOIN_TIMEOUT_SECONDS = 60;

  private static final String JAVA_HOME_PROPERTY = "java.home";
  private static final String USER_HOME_PROPERTY = "user.home";
  private static final String CLASS_PATH_PROPERTY = "java.class.path";

  private static final String SERVER_FOLDER = "server";
  private static final String MISSING_NAME = "missing";

  private static final Path RELATIVE_LIBRARY = Path.of(PARENT_FOLDER, INSTALLATION_FOLDER, NSS_LIBRARY_FILE);
  private static final Path DEVICES = Path.of("/dev");

  @TempDir
  private Path folder;

  private HelperConfiguration configuration(final Confinement confinement, final Path serverFolder) {
    final boolean confined = confinement == Confinement.ASKED;
    final Path session = this.folder.resolve(TEMPORARY_FOLDER).resolve(SESSION_FOLDER);
    return new HelperConfiguration(
      new byte[HelperProtocol.TOKEN_BYTES],
      session.resolve(SOCKET_FILE),
      this.folder.resolve(NATIVES_FOLDER),
      session.resolve(PROFILE_FOLDER),
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

  // the walk has the order of the file system; the given rules stay last
  private List<Landlock.Rule> walkSorted(final List<Landlock.Rule> rules, final int given) {
    final List<Landlock.Rule> sorted = new ArrayList<>(rules.subList(0, rules.size() - given));
    sorted.sort(Comparator.comparing(Landlock.Rule::path));
    sorted.addAll(rules.subList(rules.size() - given, rules.size()));

    return sorted;
  }

  @Test
  void shouldLetEverythingButTheHiddenFoldersBeReadAndOnlyTheWritableOnesChanged() throws IOException {
    final Path root = this.folder.toRealPath();
    final Path system = Files.createDirectories(root.resolve(SYSTEM_FOLDER));
    final Path homes = Files.createDirectories(root.resolve(HOMES_FOLDER));
    final Path home = Files.createDirectories(homes.resolve(USER_FOLDER));
    final Path otherHome = Files.createDirectories(homes.resolve(OTHER_HOME_FOLDER));
    final Path file = Files.writeString(root.resolve(PLAIN_FILE), "file");
    Files.createSymbolicLink(root.resolve(LINK_NAME), home);
    final Path natives = home.resolve(NATIVES_FOLDER);
    final Path session = root.resolve(SESSION_FOLDER);
    final List<Path> hidden = List.of(home, root, root.resolve(MISSING_NAME));
    final List<Landlock.Rule> rules = ChromiumConfinement.rules(root, hidden, List.of(natives), List.of(session));
    final List<Landlock.Rule> walkedRules = this.walkSorted(rules, 2);
    final List<Landlock.Rule> expectedWalkedRules = List.of(
      new Landlock.Rule(file, Landlock.Access.READ_ONLY),
      new Landlock.Rule(otherHome, Landlock.Access.READ_ONLY),
      new Landlock.Rule(system, Landlock.Access.READ_ONLY),
      new Landlock.Rule(natives, Landlock.Access.READ_ONLY),
      new Landlock.Rule(session, Landlock.Access.READ_WRITE)
    );
    assertEquals(expectedWalkedRules, walkedRules);
  }

  @Test
  void shouldFindAHiddenFolderByItsRealPath() throws IOException {
    final Path root = this.folder.toRealPath();
    final Path real = Files.createDirectories(root.resolve(REAL_FOLDER));
    final Path kept = Files.createDirectories(root.resolve(KEPT_FOLDER));
    final Path alias = Files.createSymbolicLink(root.resolve(ALIAS_LINK), real);
    final List<Landlock.Rule> rules = ChromiumConfinement.rules(root, List.of(alias), List.of(), List.of());
    final List<Landlock.Rule> expectedRules = List.of(new Landlock.Rule(kept, Landlock.Access.READ_ONLY));
    assertEquals(expectedRules, rules);
  }

  @Test
  @EnabledOnOs({ OS.LINUX, OS.MAC })
  void shouldGiveNothingBeneathAFolderThatCannotBeListed() throws IOException {
    final Path root = this.folder.toRealPath();
    final Path closed = Files.createDirectories(root.resolve(CLOSED_FOLDER));
    final Path hidden = Files.createDirectories(closed.resolve(HIDDEN_FOLDER));
    Files.createDirectories(closed.resolve(BESIDE_FOLDER));
    Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("-wx------"));
    try {
      assumeTrue(!Files.isReadable(closed), "The user may read every folder, so none can be closed to it!");
      final List<Landlock.Rule> rules = ChromiumConfinement.rules(root, List.of(hidden), List.of(), List.of());
      assertEquals(List.of(), rules);
    } finally {
      Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  void shouldSplitListsOfPaths() {
    final List<Path> pathsOfNothing = ChromiumConfinement.pathsOf(null);
    assertEquals(List.of(), pathsOfNothing);

    final String separator = File.pathSeparator;
    final List<Path> paths = ChromiumConfinement.pathsOf(FIRST_PATH + separator + separator + SECOND_PATH);
    final List<Path> expectedPaths = List.of(Path.of(FIRST_PATH), Path.of(SECOND_PATH));
    assertEquals(expectedPaths, paths);
  }

  @Test
  void shouldFindTheJarsOfTheJavaAgents() {
    final List<String> arguments = List.of(
      "-Xmx512m",
      "-javaagent:/agents/coverage.jar=destfile=/out/helper.exec",
      "-javaagent:/agents/plain.jar"
    );
    final List<Path> agents = ChromiumConfinement.agentsOf(arguments);
    final List<Path> expectedAgents = List.of(Path.of(COVERAGE_AGENT_FILE), Path.of(PLAIN_AGENT_FILE));
    assertEquals(expectedAgents, agents);
  }

  @Test
  void shouldFindTheFilesTheLibrariesOfTheSessionLinkTo() throws IOException {
    final Path installation = Files.createDirectories(this.folder.resolve(INSTALLATION_FOLDER));
    final Path library = Files.writeString(installation.resolve(NSS_LIBRARY_FILE), "library");
    final Path linked = Files.createDirectories(this.folder.resolve(LIBRARIES_FOLDER));
    Files.createSymbolicLink(linked.resolve(NSS_LIBRARY_FILE), library);
    Files.createSymbolicLink(linked.resolve(RELATIVE_LIBRARY_FILE), RELATIVE_LIBRARY);
    Files.writeString(linked.resolve(PLAIN_LIBRARY_FILE), "not a link");
    final List<Path> targets = ChromiumConfinement.linkedFiles(List.of(linked, this.folder.resolve(MISSING_NAME), library));
    final List<Path> sortedTargets = targets.stream().sorted().toList();
    final List<Path> expectedTargets = List.of(library, linked.resolve(RELATIVE_LIBRARY));
    assertEquals(expectedTargets, sortedTargets);
  }

  @Test
  void shouldConfineAHelperOnlyWhenItsOptionsAskOnLinuxWithLandlockAndOnMacOsWithSeatbelt() throws Exception {
    final Path server = this.folder.resolve(SERVER_FOLDER);
    try (final SeatbeltLibrary library = SeatbeltLibrary.complete()) {
      final Seatbelt seatbelt = library.seatbelt();
      final String linuxNotAsked = ChromiumConfinement.confine(
        this.configuration(Confinement.NOT_ASKED, server),
        OperatingSystem.LINUX,
        () -> 4,
        seatbelt
      );
      assertEquals(ChromiumConfinement.NOT_ASKED, linuxNotAsked);

      final String macNotAsked = ChromiumConfinement.confine(
        this.configuration(Confinement.NOT_ASKED, server),
        OperatingSystem.MAC,
        () -> 4,
        seatbelt
      );
      assertEquals(ChromiumConfinement.NOT_ASKED, macNotAsked);

      final String otherSystemReason = ChromiumConfinement.confine(
        this.configuration(Confinement.ASKED, server),
        OperatingSystem.OTHER,
        () -> 4,
        seatbelt
      );
      assertEquals("Because MCAV confines it on Linux and macOS only, Chromium runs without confinement.", otherSystemReason);

      final String noLandlockReason = ChromiumConfinement.confine(
        this.configuration(Confinement.ASKED, server),
        OperatingSystem.LINUX,
        () -> MISSING_SYSTEM_CALL_ERROR,
        seatbelt
      );
      assertEquals("Because this kernel has no Landlock, which Linux has since 5.13, Chromium runs without confinement.", noLandlockReason);

      final List<String> profiles = library.getProfiles();
      assertEquals(List.of(), profiles, "only macOS uses Seatbelt");
    }

    final String noSeatbeltReason = ChromiumConfinement.confine(
      this.configuration(Confinement.ASKED, server),
      OperatingSystem.MAC,
      () -> 4,
      new Seatbelt(name -> Optional.empty())
    );
    assertEquals(NO_SEATBELT_REASON, noSeatbeltReason);
  }

  @Test
  @DisabledOnOs(OS.MAC) // on macOS this would put this JVM in the sandbox
  void shouldLeaveTheHelperAsItIsAndSayWhyOnASystemWithoutSeatbelt() throws IOException {
    final HelperConfiguration configuration = this.configuration(Confinement.ASKED, this.folder.resolve(SERVER_FOLDER));
    final String noSeatbeltReason = ChromiumConfinement.confine(configuration, OperatingSystem.MAC);
    assertEquals(NO_SEATBELT_REASON, noSeatbeltReason);
  }

  @Test
  void shouldConfineTheWholeHelperWithAProfileOfTheRealPathsOnMacOs() throws Exception {
    final Path server = Files.createDirectories(this.folder.resolve(SERVER_FOLDER));
    final HelperConfiguration configuration = this.configuration(Confinement.ASKED, server);
    final Path session = Files.createDirectories(configuration.getSocket().getParent());
    final String said;
    final List<String> profiles;
    try (final SeatbeltLibrary library = SeatbeltLibrary.complete()) {
      said = ChromiumConfinement.confine(configuration, OperatingSystem.MAC, () -> MISSING_SYSTEM_CALL_ERROR, library.seatbelt());
      profiles = List.copyOf(library.getProfiles());
    }

    assertEquals(ChromiumConfinement.CONFINED, said);

    final int profileCount = profiles.size();
    assertEquals(1, profileCount);

    final String profile = profiles.getFirst();
    final String home = Path.of(System.getProperty(USER_HOME_PROPERTY)).toRealPath().toString();
    final String temporary = session.getParent().toRealPath().toString();
    final boolean deniesThenAllowsReading = profile.startsWith(
      "(version 1)\n(allow default)\n(deny file-read-data (subpath " +
        Seatbelt.quote(server.toRealPath().toString()) +
        ") (subpath " +
        Seatbelt.quote(home) +
        ") (subpath " +
        Seatbelt.quote(temporary) +
        "))\n(allow file-read-data (subpath " +
        Seatbelt.quote(Path.of(System.getProperty(JAVA_HOME_PROPERTY)).toRealPath().toString()) +
        ") (subpath " +
        Seatbelt.quote(this.folder.resolve(NATIVES_FOLDER).toAbsolutePath().normalize().toString()) +
        ")"
    );
    assertTrue(deniesThenAllowsReading, profile);

    final boolean allowsWritingOnlyTheSession = profile.endsWith(
      "(deny file-write*)\n(allow file-write* (subpath " +
        Seatbelt.quote(session.toRealPath().toString()) +
        ") (subpath " +
        Seatbelt.quote(ChromiumConfinement.realPaths(List.of(DEVICES)).getFirst().toString()) +
        "))\n"
    );
    assertTrue(allowsWritingOnlyTheSession, profile);

    for (final Path entry : ChromiumConfinement.realPaths(ChromiumConfinement.pathsOf(System.getProperty(CLASS_PATH_PROPERTY)))) {
      final boolean allowsReadingEntry = profile.contains("(subpath " + Seatbelt.quote(entry.toString()) + ")");
      assertTrue(allowsReadingEntry, () -> entry + " may be read");
    }
  }

  @Test
  void shouldResolveLinksAndKeepMissingPathsAbsoluteInRealPaths() throws IOException {
    final Path real = Files.createDirectories(this.folder.resolve(REAL_FOLDER));
    final Path link = Files.createSymbolicLink(this.folder.resolve(LINK_NAME), real);
    final Path missing = this.folder.resolve(MISSING_NAME).resolve(PARENT_FOLDER).resolve(GONE_FOLDER);
    final List<Path> resolved = ChromiumConfinement.realPaths(List.of(link, missing));
    final List<Path> expectedResolvedPaths = List.of(real.toRealPath(), this.folder.resolve(GONE_FOLDER).toAbsolutePath());
    assertEquals(expectedResolvedPaths, resolved);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldNotLetAConfinedHelperReadTheFolderOfTheServerOrTheTemporaryFolder() throws Exception {
    final int version = Landlock.version();
    assumeTrue(version >= 1, () -> MISSING_LANDLOCK_MESSAGE.formatted(Landlock.describeMissing(version)));
    final Path server = Files.createDirectories(this.folder.resolve(SERVER_FOLDER));
    final Path serverConfiguration = Files.writeString(server.resolve(SERVER_CONFIGURATION_FILE), SERVER_CONFIGURATION_TEXT);
    final Path otherSession = Files.createDirectories(this.folder.resolve(TEMPORARY_FOLDER).resolve(OTHER_SESSION_FOLDER));
    final Path cookies = Files.writeString(otherSession.resolve(COOKIES_FILE), COOKIE_TEXT);
    final HelperConfiguration configuration = this.configuration(Confinement.ASKED, server);
    final Path session = Files.createDirectories(configuration.getSocket().getParent());
    final AtomicReference<String> said = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread thread = new Thread(
      () -> this.confineAndCheckAccess(configuration, serverConfiguration, cookies, session, said, failure),
      CONFINEMENT_THREAD_NAME
    );
    thread.start();
    thread.join(TimeUnit.SECONDS.toMillis(JOIN_TIMEOUT_SECONDS));
    final boolean alive = thread.isAlive();
    assertFalse(alive);

    final Throwable confinedFailure = failure.get();
    assertNull(confinedFailure, () -> CONFINED_THREAD_FAILURE_PREFIX + failure.get());

    final String saidConfinement = said.get();
    assertEquals(ChromiumConfinement.CONFINED, saidConfinement);

    final String profileText = Files.readString(session.resolve(PROFILE_TEXT_FILE), StandardCharsets.UTF_8);
    assertEquals(PROFILE_FOLDER, profileText);

    final boolean readable = Files.isReadable(serverConfiguration);
    assertTrue(readable, "the other threads are free");
  }

  private void confineAndCheckAccess(
    final HelperConfiguration configuration,
    final Path serverConfiguration,
    final Path cookies,
    final Path session,
    final AtomicReference<String> said,
    final AtomicReference<Throwable> failure
  ) {
    try {
      said.set(ChromiumConfinement.confine(configuration, OperatingSystem.LINUX));
      assertThrows(AccessDeniedException.class, () -> Files.readString(serverConfiguration, StandardCharsets.UTF_8));
      assertThrows(AccessDeniedException.class, () -> Files.readString(cookies, StandardCharsets.UTF_8));

      Files.writeString(session.resolve(PROFILE_TEXT_FILE), PROFILE_FOLDER);
      assertThrows(AccessDeniedException.class, () -> Files.writeString(this.folder.resolve(OUTSIDE_FILE), "outside"));
    } catch (final Throwable exception) {
      failure.set(exception);
    }
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldConfineWithTheFirstVersionOfLandlock() throws Exception {
    final int version = Landlock.version();
    assumeTrue(version >= 1, () -> MISSING_LANDLOCK_MESSAGE.formatted(Landlock.describeMissing(version)));
    final Path server = Files.createDirectories(this.folder.resolve(SERVER_FOLDER));
    final HelperConfiguration configuration = this.configuration(Confinement.ASKED, server);
    Files.createDirectories(configuration.getSocket().getParent());
    final AtomicReference<String> said = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread thread = new Thread(() -> this.confineWithTheFirstVersion(configuration, said, failure), CONFINEMENT_THREAD_NAME);
    thread.start();
    thread.join(TimeUnit.SECONDS.toMillis(JOIN_TIMEOUT_SECONDS));
    final boolean alive = thread.isAlive();
    assertFalse(alive);

    final Throwable confinedFailure = failure.get();
    assertNull(confinedFailure, () -> CONFINED_THREAD_FAILURE_PREFIX + failure.get());

    final String saidConfinement = said.get();
    assertEquals(ChromiumConfinement.CONFINED, saidConfinement);
  }

  private void confineWithTheFirstVersion(
    final HelperConfiguration configuration,
    final AtomicReference<String> said,
    final AtomicReference<Throwable> failure
  ) {
    try {
      said.set(ChromiumConfinement.confine(configuration, OperatingSystem.LINUX, () -> 1, new Seatbelt(name -> Optional.empty())));
    } catch (final Throwable exception) {
      failure.set(exception);
    }
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldGiveNothingBeneathAFolderThatCannotBeListedEvenByRoot() throws Exception {
    final int version = Landlock.version();
    assumeTrue(version >= 1, () -> MISSING_LANDLOCK_MESSAGE.formatted(Landlock.describeMissing(version)));
    final Path root = this.folder.toRealPath();
    final Path beside = Files.createDirectories(root.resolve(BESIDE_FOLDER));
    final List<Landlock.Rule> openRules = ChromiumConfinement.rules(root, List.of(), List.of(), List.of());
    final List<Landlock.Rule> expectedOpenRules = List.of(new Landlock.Rule(beside, Landlock.Access.READ_ONLY));
    assertEquals(expectedOpenRules, openRules);

    final AtomicReference<List<Landlock.Rule>> closedRules = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread thread = new Thread(() -> this.closeEverythingAndWalk(version, root, closedRules, failure), "closed-walk-test");
    thread.start();
    thread.join(TimeUnit.SECONDS.toMillis(JOIN_TIMEOUT_SECONDS));
    final boolean alive = thread.isAlive();
    assertFalse(alive);

    final Throwable closedFailure = failure.get();
    assertNull(closedFailure, () -> "the closed thread failed: " + failure.get());

    final List<Landlock.Rule> rules = closedRules.get();
    assertEquals(List.of(), rules);
  }

  // a ruleset without rules closes every folder to the thread, unlike permissions, which root ignores
  private void closeEverythingAndWalk(
    final int version,
    final Path root,
    final AtomicReference<List<Landlock.Rule>> rules,
    final AtomicReference<Throwable> failure
  ) {
    try {
      Landlock.restrictThread(version, List.of());
      rules.set(ChromiumConfinement.rules(root, List.of(), List.of(), List.of()));
    } catch (final Throwable exception) {
      failure.set(exception);
    }
  }

  @Test
  @EnabledOnOs(OS.MAC)
  void shouldNotLetAConfinedHelperOrWhatItStartsReadTheFolderOfTheServerOrTheTemporaryFolderOnMacOs() throws Exception {
    final Path server = Files.createDirectories(this.folder.resolve(SERVER_FOLDER));
    final Path serverConfiguration = Files.writeString(server.resolve(SERVER_CONFIGURATION_FILE), SERVER_CONFIGURATION_TEXT);
    final Path otherSession = Files.createDirectories(this.folder.resolve(TEMPORARY_FOLDER).resolve(OTHER_SESSION_FOLDER));
    final Path cookies = Files.writeString(otherSession.resolve(COOKIES_FILE), COOKIE_TEXT);
    final HelperConfiguration configuration = this.configuration(Confinement.ASKED, server);
    final Path session = Files.createDirectories(configuration.getSocket().getParent());
    final Path outside = this.folder.resolve(OUTSIDE_FILE);
    // Seatbelt holds for a whole process and cannot be lifted, so a JVM of its own confines itself
    final Process probe = new ProcessBuilder(
      Path.of(System.getProperty(JAVA_HOME_PROPERTY), JAVA_BIN_FOLDER, JAVA_EXECUTABLE).toString(),
      "--enable-native-access=ALL-UNNAMED",
      "-cp",
      System.getProperty(CLASS_PATH_PROPERTY),
      SeatbeltProbeMain.class.getName(),
      server.toString(),
      configuration.getSocket().toString(),
      configuration.getNatives().toString(),
      serverConfiguration.toString(),
      cookies.toString(),
      outside.toString()
    )
      .redirectErrorStream(true)
      .start();
    final String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    final boolean exited = probe.waitFor(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(exited, output);

    final int exitCode = probe.exitValue();
    assertEquals(0, exitCode, output);

    final List<String> outputLines = output
      .lines()
      .filter(line -> !line.startsWith("WARNING"))
      .toList();
    final List<String> expectedOutputLines = List.of(
      "said " + ChromiumConfinement.CONFINED,
      "server folder: denied",
      "other session: denied",
      "session: written",
      "outside: denied",
      "started cat: refused",
      "sandboxed: true"
    );
    assertEquals(expectedOutputLines, outputLines, output);

    final String profileText = Files.readString(session.resolve(PROFILE_TEXT_FILE), StandardCharsets.UTF_8);
    assertEquals(PROFILE_FOLDER, profileText);

    final boolean outsideWritten = Files.exists(outside);
    assertFalse(outsideWritten);

    final boolean readable = Files.isReadable(serverConfiguration);
    assertTrue(readable, "this process is free");
  }

  private enum Confinement {
    ASKED,
    NOT_ASKED,
  }
}
