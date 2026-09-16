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
package me.brandonli.mcav.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link ExecutableFinder}.
 */
final class ExecutableFinderTest {

  @TempDir
  private Path directory;

  private Path createProgram(final String folder, final String fileName) throws IOException {
    final Path parent = this.directory.resolve(folder);
    return writeProgram(parent, fileName);
  }

  private static Path writeProgram(final Path parent, final String fileName) throws IOException {
    Files.createDirectories(parent);
    final Path program = parent.resolve(fileName);
    Files.writeString(program, "program");
    makeExecutable(program);
    return program;
  }

  private static void makeExecutable(final Path program) {
    final File file = program.toFile();
    final boolean madeExecutable = file.setExecutable(true);
    assertTrue(madeExecutable, program::toString);
  }

  private static void writeLines(final Path file, final String... lines) throws IOException {
    final List<String> content = List.of(lines);
    Files.write(file, content);
  }

  private static Path workingDirectory() {
    final Path relative = Path.of("");
    return relative.toAbsolutePath();
  }

  private static void denyExecution(final Path file) throws IOException {
    final PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
    if (posix != null) {
      final Set<PosixFilePermission> readWrite = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
      posix.setPermissions(readWrite);
      return;
    }

    // Windows has no execute bit, so execution is denied to the current user through the access control list
    final AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
    final FileSystem fileSystem = file.getFileSystem();
    final UserPrincipalLookupService lookup = fileSystem.getUserPrincipalLookupService();
    final String userName = System.getProperty("user.name");
    final UserPrincipal user = lookup.lookupPrincipalByName(userName);
    final AclEntry.Builder builder = AclEntry.newBuilder();
    builder.setType(AclEntryType.DENY);
    builder.setPrincipal(user);
    builder.setPermissions(AclEntryPermission.EXECUTE);
    final AclEntry deny = builder.build();

    final List<AclEntry> current = acl.getAcl();
    final List<AclEntry> entries = new ArrayList<>(current);
    entries.addFirst(deny);
    acl.setAcl(entries);
  }

  // a root folder without etc, opt or usr, so no fixed folder of the operating system is found
  private Path emptyRoot() {
    return this.directory.resolve("empty-root");
  }

  private ExecutableFinder finder(final OS os, final String path) {
    final Map<String, String> environment = Map.of("PATH", path);
    final Path root = this.emptyRoot();
    return new ExecutableFinder(os, environment, root);
  }

  private ExecutableFinder finderWithoutPath(final OS os) {
    final Map<String, String> environment = Map.of();
    final Path root = this.emptyRoot();
    return new ExecutableFinder(os, environment, root);
  }

  private static String pathOf(final Path... folders) {
    final StringBuilder builder = new StringBuilder();
    for (final Path folder : folders) {
      if (!builder.isEmpty()) {
        builder.append(File.pathSeparator);
      }
      final String text = folder.toString();
      builder.append(text);
    }
    return builder.toString();
  }

  private static Optional<Path> absolute(final Path program) {
    final Path absolutePath = program.toAbsolutePath();
    return Optional.of(absolutePath);
  }

  @Test
  void findsProgramsInThePathDirectories() throws IOException {
    final Path first = this.directory.resolve("first");
    Files.createDirectories(first);
    final Path program = this.createProgram("second", "qemu-system-x86_64");
    final Path second = program.getParent();
    final String path = pathOf(first, second);
    final ExecutableFinder finder = this.finder(OS.LINUX, path);
    final Optional<Path> found = finder.find("qemu-system-x86_64");
    final Optional<Path> expected = absolute(program);
    assertEquals(expected, found);
  }

  @Test
  void prefersTheFirstPathDirectory() throws IOException {
    final Path early = this.createProgram("early", "tool");
    final Path late = this.createProgram("late", "tool");
    final Path earlyFolder = early.getParent();
    final Path lateFolder = late.getParent();
    final String path = pathOf(earlyFolder, lateFolder);
    final ExecutableFinder finder = this.finder(OS.LINUX, path);
    final Optional<Path> found = finder.find("tool");
    final Optional<Path> expected = absolute(early);
    assertEquals(expected, found);
  }

  @Test
  void addsTheExecutableExtensionsOnWindows() throws IOException {
    final Path exe = this.createProgram("exe", "qemu.exe");
    final Path batch = this.createProgram("batch", "script.bat");
    final Path command = this.createProgram("command", "legacy.cmd");
    final Path exeFolder = exe.getParent();
    final Path batchFolder = batch.getParent();
    final Path commandFolder = command.getParent();
    final String path = pathOf(exeFolder, batchFolder, commandFolder);
    final ExecutableFinder windows = this.finder(OS.WINDOWS, path);
    final ExecutableFinder linux = this.finder(OS.LINUX, path);
    final Optional<Path> windowsExe = windows.find("qemu");
    final Optional<Path> windowsBatch = windows.find("script");
    final Optional<Path> windowsCommand = windows.find("legacy");
    final Optional<Path> linuxExe = linux.find("qemu");
    final Optional<Path> expectedExe = absolute(exe);
    final Optional<Path> expectedBatch = absolute(batch);
    final Optional<Path> expectedCommand = absolute(command);
    final boolean linuxExeIsEmpty = linuxExe.isEmpty();
    assertEquals(expectedExe, windowsExe);
    assertEquals(expectedBatch, windowsBatch);
    assertEquals(expectedCommand, windowsCommand);
    assertTrue(linuxExeIsEmpty);
  }

  @Test
  void prefersTheExtensionsInTheOrderWindowsRunsThem() throws IOException {
    final Path com = this.createProgram("both", "tool.com");
    this.createProgram("both", "tool.exe");
    final Path folder = com.getParent();
    final String path = pathOf(folder);
    final ExecutableFinder windows = this.finder(OS.WINDOWS, path);
    final Optional<Path> found = windows.find("tool");
    final Optional<Path> expected = absolute(com);
    assertEquals(expected, found);
  }

  @Test
  void findsOnlyFilesWithAnExecutableExtensionOnWindows() throws IOException {
    // a file without an extension, or with another one, could be planted to run in place of the real program
    final Path bare = this.createProgram("planted", "qemu");
    this.createProgram("planted", "qemu.txt");
    final Path named = this.createProgram("planted", "named.EXE");
    final Path folder = bare.getParent();
    final String path = pathOf(folder);
    final ExecutableFinder windows = this.finder(OS.WINDOWS, path);
    final Optional<Path> bareName = windows.find("qemu");
    final Optional<Path> fullName = windows.find("named.EXE");
    final boolean bareIsEmpty = bareName.isEmpty();
    final Optional<Path> expectedFull = absolute(named);
    assertTrue(bareIsEmpty, bareName::toString);
    assertEquals(expectedFull, fullName);
  }

  @Test
  void readsThePathVariableRegardlessOfItsCase() throws IOException {
    final Path program = this.createProgram("mixed", "tool.exe");
    final Path folder = program.getParent();
    final String path = pathOf(folder);
    final Map<String, String> environment = Map.of("HOME", "/home/user", "Path", path);
    final Path root = this.emptyRoot();
    final ExecutableFinder finder = new ExecutableFinder(OS.WINDOWS, environment, root);
    final Optional<Path> found = finder.find("tool");
    final Optional<Path> expected = absolute(program);
    assertEquals(expected, found);
  }

  @Test
  void findsNothingWithoutAPathVariable() {
    final Map<String, String> environment = Map.of("HOME", "/home/user");
    final Path root = this.emptyRoot();
    final ExecutableFinder finder = new ExecutableFinder(OS.LINUX, environment, root);
    final Optional<Path> found = finder.find("qemu-system-x86_64");
    final boolean foundIsEmpty = found.isEmpty();
    assertTrue(foundIsEmpty);
  }

  @Test
  void skipsBlankAndBrokenPathEntries() throws IOException {
    final Path program = this.createProgram("valid", "tool");
    final Path folder = program.getParent();
    final String validFolder = folder.toString();
    // a NUL character is not allowed in a path on any operating system
    final String path = String.join(File.pathSeparator, "", "  ", "bro\0ken", validFolder);
    final ExecutableFinder finder = this.finder(OS.LINUX, path);
    final Optional<Path> found = finder.find("tool");
    final Optional<Path> expected = absolute(program);
    assertEquals(expected, found);
  }

  @Test
  void ignoresFilesThatMayNotBeExecuted() throws IOException {
    final Path program = this.createProgram("plain", "notes");
    denyExecution(program);
    final boolean executable = Files.isExecutable(program);
    assertFalse(executable, "the test needs a file without execute permission");
    final Path folder = program.getParent();
    final String path = pathOf(folder);
    final ExecutableFinder finder = this.finder(OS.LINUX, path);
    final Optional<Path> found = finder.find("notes");
    final boolean foundIsEmpty = found.isEmpty();
    assertTrue(foundIsEmpty);
  }

  @Test
  void ignoresDirectoriesWithTheNameOfTheProgram() throws IOException {
    final Path folder = this.directory.resolve("bin");
    final Path lookalike = folder.resolve("tool");
    Files.createDirectories(lookalike);
    final String path = pathOf(folder);
    final ExecutableFinder finder = this.finder(OS.LINUX, path);
    final Optional<Path> found = finder.find("tool");
    final boolean foundIsEmpty = found.isEmpty();
    assertTrue(foundIsEmpty);
  }

  @Test
  void looksUpNamesWithAFolderInThatFolderAndReturnsAbsolutePaths() throws IOException {
    final Path program = this.createProgram("direct", "qemu");
    final String absoluteName = program.toString();
    final Path workingDirectory = workingDirectory();
    final Path relativeProgram = workingDirectory.relativize(program);
    final String relativeName = relativeProgram.toString();
    final ExecutableFinder finder = this.finderWithoutPath(OS.LINUX);
    final Optional<Path> fromAbsolute = finder.find(absoluteName);
    final Optional<Path> fromRelative = finder.find(relativeName);
    final Optional<Path> expected = absolute(program);
    final Path found = fromRelative.orElseThrow();
    final boolean isAbsolute = found.isAbsolute();
    final boolean sameFile = Files.isSameFile(program, found);
    assertEquals(expected, fromAbsolute);
    assertTrue(isAbsolute, found::toString);
    assertTrue(sameFile);
  }

  @Test
  void addsExtensionsToPathsOnWindows() throws IOException {
    final Path program = this.createProgram("direct", "qemu.exe");
    final Path folder = program.getParent();
    final Path withoutExtension = folder.resolve("qemu");
    final String name = withoutExtension.toString();
    final ExecutableFinder finder = this.finderWithoutPath(OS.WINDOWS);
    final Optional<Path> found = finder.find(name);
    final Optional<Path> expected = absolute(program);
    assertEquals(expected, found);
  }

  @Test
  void neverSearchesTheWorkingDirectory() throws IOException {
    final Path workingDirectory = workingDirectory();
    final Path planted = workingDirectory.resolve("mcav-planted-program.exe");
    Files.writeString(planted, "planted");
    try {
      makeExecutable(planted);
      final ExecutableFinder windows = this.finderWithoutPath(OS.WINDOWS);
      final ExecutableFinder linux = this.finderWithoutPath(OS.LINUX);
      final Optional<Path> onWindows = windows.find("mcav-planted-program");
      final Optional<Path> onLinux = linux.find("mcav-planted-program.exe");
      final boolean windowsIsEmpty = onWindows.isEmpty();
      final boolean linuxIsEmpty = onLinux.isEmpty();
      assertTrue(windowsIsEmpty, onWindows::toString);
      assertTrue(linuxIsEmpty, onLinux::toString);
    } finally {
      Files.deleteIfExists(planted);
    }
  }

  @Test
  void searchesTheFoldersMacOsAddsToLoginShells() throws IOException {
    final Path listed = this.createProgram("listed", "qemu-system-aarch64");
    final Path fromPathsD = this.createProgram("from-paths-d", "qemu-img");
    final Path listedFolder = listed.getParent();
    final Path pathsDFolder = fromPathsD.getParent();
    final Path root = this.createMacRoot(listedFolder, pathsDFolder);

    final Map<String, String> environment = Map.of("PATH", "");
    final ExecutableFinder mac = new ExecutableFinder(OS.MAC, environment, root);
    final ExecutableFinder linux = new ExecutableFinder(OS.LINUX, environment, root);
    final Optional<Path> listedOnMac = mac.find("qemu-system-aarch64");
    final Optional<Path> pathsDOnMac = mac.find("qemu-img");
    final Optional<Path> listedOnLinux = linux.find("qemu-system-aarch64");
    final Optional<Path> expectedListed = absolute(listed);
    final Optional<Path> expectedPathsD = absolute(fromPathsD);
    final boolean linuxIsEmpty = listedOnLinux.isEmpty();
    assertEquals(expectedListed, listedOnMac);
    assertEquals(expectedPathsD, pathsDOnMac);
    assertTrue(linuxIsEmpty);
  }

  /**
   * Creates a macOS root folder whose {@code /etc/paths} lists one folder, between blank lines, and whose
   * {@code /etc/paths.d} lists another in a file, next to a folder that must be skipped.
   */
  private Path createMacRoot(final Path listedFolder, final Path pathsDFolder) throws IOException {
    final Path root = this.directory.resolve("mac-root");
    final Path etc = root.resolve("etc");
    final Path pathsD = etc.resolve("paths.d");
    Files.createDirectories(pathsD);

    final String listedName = listedFolder.toString();
    final Path pathsFile = etc.resolve("paths");
    writeLines(pathsFile, "", listedName, "   ");

    final String pathsDName = pathsDFolder.toString();
    final Path pathsDFile = pathsD.resolve("40-qemu");
    writeLines(pathsDFile, pathsDName);

    final Path nested = pathsD.resolve("folder-is-ignored");
    Files.createDirectories(nested);
    return root;
  }

  @Test
  void readsThePathsFilesOfMacOsInNameOrder() throws IOException {
    final Path first = this.createProgram("first", "tool");
    final Path second = this.createProgram("second", "tool");
    final Path root = this.directory.resolve("mac-root");
    final Path pathsD = root.resolve("etc/paths.d");
    Files.createDirectories(pathsD);
    final Path firstFolder = first.getParent();
    final Path secondFolder = second.getParent();
    final String firstName = firstFolder.toString();
    final String secondName = secondFolder.toString();
    final Path secondFile = pathsD.resolve("20-second");
    final Path firstFile = pathsD.resolve("10-first");
    writeLines(secondFile, secondName);
    writeLines(firstFile, firstName);
    final Map<String, String> environment = Map.of("PATH", "");
    final ExecutableFinder mac = new ExecutableFinder(OS.MAC, environment, root);
    final Optional<Path> found = mac.find("tool");
    final Optional<Path> expected = absolute(first);
    assertEquals(expected, found);
  }

  @Test
  void searchesTheInstallFoldersOfHomebrewOnMacOs() throws IOException {
    final Path root = this.directory.resolve("mac-root");
    final Path appleSilicon = root.resolve("opt/homebrew/bin");
    final Path intel = root.resolve("usr/local/bin");
    final Path arm = writeProgram(appleSilicon, "qemu-system-aarch64");
    final Path x86 = writeProgram(intel, "qemu-system-x86_64");

    final Map<String, String> environment = Map.of("PATH", "");
    final ExecutableFinder mac = new ExecutableFinder(OS.MAC, environment, root);
    final Optional<Path> armFound = mac.find("qemu-system-aarch64");
    final Optional<Path> x86Found = mac.find("qemu-system-x86_64");
    final Optional<Path> expectedArm = absolute(arm);
    final Optional<Path> expectedX86 = absolute(x86);
    assertEquals(expectedArm, armFound);
    assertEquals(expectedX86, x86Found);
  }

  @Test
  void searchesTheSystemFoldersOnLinuxAndFreeBsd() throws IOException {
    final Path root = this.directory.resolve("unix-root");
    final Path usrBin = root.resolve("usr/bin");
    final Path usrLocalBin = root.resolve("usr/local/bin");
    final Path system = writeProgram(usrBin, "qemu-system-x86_64");
    final Path local = writeProgram(usrLocalBin, "qemu-system-riscv64");

    final Map<String, String> environment = Map.of("PATH", "");
    final ExecutableFinder linux = new ExecutableFinder(OS.LINUX, environment, root);
    final ExecutableFinder freeBsd = new ExecutableFinder(OS.FREEBSD, environment, root);
    final ExecutableFinder mac = new ExecutableFinder(OS.MAC, environment, root);
    final Optional<Path> onLinux = linux.find("qemu-system-x86_64");
    final Optional<Path> localOnFreeBsd = freeBsd.find("qemu-system-riscv64");
    final Optional<Path> systemOnMac = mac.find("qemu-system-x86_64");
    final Optional<Path> expectedSystem = absolute(system);
    final Optional<Path> expectedLocal = absolute(local);
    final boolean macIsEmpty = systemOnMac.isEmpty();
    assertEquals(expectedSystem, onLinux);
    assertEquals(expectedLocal, localOnFreeBsd);
    assertTrue(macIsEmpty, "macOS lists /usr/bin in /etc/paths instead");
  }

  @Test
  void searchesOnlyThePathOnAnUnknownOperatingSystem() throws IOException {
    final Path root = this.directory.resolve("unknown-root");
    final Path usrBin = root.resolve("usr/bin");
    writeProgram(usrBin, "qemu-system-x86_64");
    final Path onPath = this.createProgram("on-path", "qemu-system-arm");
    final Path pathFolder = onPath.getParent();
    final String path = pathOf(pathFolder);

    final Map<String, String> environment = Map.of("PATH", path);
    final ExecutableFinder other = new ExecutableFinder(OS.OTHER, environment, root);
    final Optional<Path> inSystemFolder = other.find("qemu-system-x86_64");
    final Optional<Path> inPath = other.find("qemu-system-arm");

    final boolean systemFolderIsEmpty = inSystemFolder.isEmpty();
    final Optional<Path> expected = absolute(onPath);
    assertTrue(systemFolderIsEmpty, inSystemFolder::toString);
    assertEquals(expected, inPath);
  }

  @Test
  void searchesTheQemuFolderOfProgramFilesOnWindows() throws IOException {
    final Path program = this.createProgram("Program Files/qemu", "qemu-system-x86_64.exe");
    final Path qemuFolder = program.getParent();
    final Path programFiles = qemuFolder.getParent();
    final String programFilesName = programFiles.toString();
    final Map<String, String> environment = Map.of("PATH", "", "ProgramFiles", programFilesName);
    final Path root = this.emptyRoot();
    final ExecutableFinder windows = new ExecutableFinder(OS.WINDOWS, environment, root);
    final Optional<Path> found = windows.find("qemu-system-x86_64");
    final Optional<Path> expected = absolute(program);
    assertEquals(expected, found);
  }

  @Test
  void searchesTheDefaultProgramFilesFolderWithoutItsVariable() {
    final Path defaultProgram = Path.of("C:\\Program Files", "qemu", "qemu-system-x86_64.exe");
    final boolean installed = Files.isRegularFile(defaultProgram);
    final ExecutableFinder windows = this.finderWithoutPath(OS.WINDOWS);
    final Optional<Path> found = windows.find("qemu-system-x86_64");
    final boolean present = found.isPresent();
    assertEquals(installed, present);
  }

  @Test
  void ignoresAMissingOrUnreadablePathsFile() throws IOException {
    final ExecutableFinder missing = this.finder(OS.MAC, "");
    final Path root = this.directory.resolve("broken-root");
    final Path etc = root.resolve("etc");
    Files.createDirectories(etc);
    final Path invalid = etc.resolve("paths");
    // bytes that are not UTF-8 make reading the file fail
    Files.write(invalid, new byte[] { (byte) 0xC3, (byte) 0x28, '\n' });
    final Map<String, String> environment = Map.of("PATH", "");
    final ExecutableFinder unreadable = new ExecutableFinder(OS.MAC, environment, root);
    final Optional<Path> fromMissing = missing.find("tool");
    final Optional<Path> fromUnreadable = unreadable.find("tool");
    final boolean fromMissingIsEmpty = fromMissing.isEmpty();
    final boolean fromUnreadableIsEmpty = fromUnreadable.isEmpty();
    assertTrue(fromMissingIsEmpty);
    assertTrue(fromUnreadableIsEmpty);
  }

  @Test
  void findsProgramsWithTheRealEnvironment() {
    final ExecutableFinder finder = new ExecutableFinder();
    final OS os = OSUtils.getOS();
    // the command interpreter of the operating system is always installed and on the PATH
    final String shell = os == OS.WINDOWS ? "cmd" : "sh";
    final Optional<Path> found = finder.find(shell);
    final Optional<Path> missing = finder.find("mcav-program-that-does-not-exist");
    final boolean foundIsPresent = found.isPresent();
    final boolean missingIsEmpty = missing.isEmpty();
    assertTrue(foundIsPresent, shell);
    assertTrue(missingIsEmpty);
  }

  @Test
  void rejectsInvalidNames() {
    final ExecutableFinder finder = this.finderWithoutPath(OS.LINUX);
    assertThrows(NullPointerException.class, () -> finder.find(null));
    final IllegalArgumentException blank = assertThrows(IllegalArgumentException.class, () -> finder.find(" "));
    // the blank name is refused by the precondition, not by a file system that dislikes the path
    final String message = blank.getMessage();
    assertEquals("Name must not be blank", message);
  }
}
