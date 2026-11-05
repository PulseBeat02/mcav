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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class ExecutableFinderTest {

  private static final String PATH_VARIABLE = "PATH";
  private static final String HOME_VARIABLE = "HOME";
  private static final String HOME_FOLDER = "/home/user";

  private static final String X86_64_COMMAND = "qemu-system-x86_64";
  private static final String X86_64_EXECUTABLE = "qemu-system-x86_64.exe";
  private static final String AARCH64_COMMAND = "qemu-system-aarch64";
  private static final String RISCV64_COMMAND = "qemu-system-riscv64";
  private static final String ARM_COMMAND = "qemu-system-arm";
  private static final String IMAGE_COMMAND = "qemu-img";

  private static final String QEMU = "qemu";
  private static final String QEMU_EXECUTABLE = "qemu.exe";
  private static final String QEMU_TEXT_FILE = "qemu.txt";
  private static final String UPPER_CASE_EXECUTABLE = "named.EXE";

  private static final String TOOL = "tool";
  private static final String TOOL_EXECUTABLE = "tool.exe";
  private static final String TOOL_COM_PROGRAM = "tool.com";

  private static final String BATCH_NAME = "script";
  private static final String BATCH_FILE = "script.bat";
  private static final String COMMAND_NAME = "legacy";
  private static final String COMMAND_FILE = "legacy.cmd";

  private static final String NOTES_FILE = "notes";

  private static final String PLANTED_PROGRAM = "mcav-planted-program";
  private static final String PLANTED_EXECUTABLE = "mcav-planted-program.exe";
  private static final String MISSING_PROGRAM = "mcav-program-that-does-not-exist";

  private static final String WINDOWS_SHELL = "cmd";
  private static final String UNIX_SHELL = "sh";

  private static final String FIRST_FOLDER = "first";
  private static final String SECOND_FOLDER = "second";
  private static final String EARLY_FOLDER = "early";
  private static final String LATE_FOLDER = "late";
  private static final String EXECUTABLE_FOLDER = "exe";
  private static final String BATCH_FOLDER = "batch";
  private static final String COMMAND_FOLDER = "command";
  private static final String BOTH_FOLDER = "both";
  private static final String PLANTED_FOLDER = "planted";
  private static final String MIXED_FOLDER = "mixed";
  private static final String VALID_FOLDER = "valid";
  private static final String BROKEN_FOLDER = "bro\0ken";
  private static final String PLAIN_FOLDER = "plain";
  private static final String BIN_FOLDER = "bin";
  private static final String DIRECT_FOLDER = "direct";
  private static final String LISTED_FOLDER = "listed";
  private static final String PATHS_D_PROGRAM_FOLDER = "from-paths-d";
  private static final String ON_PATH_FOLDER = "on-path";

  private static final String EMPTY_ROOT_FOLDER = "empty-root";
  private static final String MAC_ROOT_FOLDER = "mac-root";
  private static final String UNIX_ROOT_FOLDER = "unix-root";
  private static final String UNKNOWN_ROOT_FOLDER = "unknown-root";
  private static final String BROKEN_ROOT_FOLDER = "broken-root";

  private static final String ETC_FOLDER = "etc";
  private static final String PATHS_FILE = "paths";
  private static final String PATHS_D_FOLDER = "paths.d";
  private static final String ETC_PATHS_D_FOLDER = "etc/paths.d";
  private static final String QEMU_PATHS_FILE = "40-qemu";
  private static final String FIRST_PATHS_FILE = "10-first";
  private static final String SECOND_PATHS_FILE = "20-second";
  private static final String IGNORED_FOLDER = "folder-is-ignored";

  private static final String HOMEBREW_APPLE_SILICON_FOLDER = "opt/homebrew/bin";
  private static final String USR_LOCAL_BIN_FOLDER = "usr/local/bin";
  private static final String USR_BIN_FOLDER = "usr/bin";

  private static final String PROGRAM_FILES_QEMU_FOLDER = "Program Files/qemu";
  private static final String DEFAULT_PROGRAM_FILES_QEMU_FOLDER = "default-program-files/qemu";
  private static final String DEFAULT_PROGRAM_FILES = "C:\\Program Files";

  @TempDir
  private Path directory;

  private Path createProgram(final String folder, final String fileName) throws IOException {
    final Path parent = this.directory.resolve(folder);
    return writeProgram(parent, fileName);
  }

  private Path writeProgram(final Path parent, final String fileName) throws IOException {
    Files.createDirectories(parent);
    final Path program = parent.resolve(fileName);
    Files.writeString(program, "program");
    this.makeExecutable(program);
    return program;
  }

  private void makeExecutable(final Path program) {
    final File file = program.toFile();
    final boolean madeExecutable = file.setExecutable(true);
    assertTrue(madeExecutable, program::toString);
  }

  private void writeLines(final Path file, final String... lines) throws IOException {
    final List<String> content = List.of(lines);
    Files.write(file, content);
  }

  private Path workingDirectory() {
    final Path relative = Path.of("");
    return relative.toAbsolutePath();
  }

  private Path emptyRoot() {
    return this.directory.resolve(EMPTY_ROOT_FOLDER);
  }

  private ExecutableFinder finder(final Os os, final String path) {
    final Map<String, String> environment = Map.of(PATH_VARIABLE, path);
    final Path root = this.emptyRoot();
    return new ExecutableFinder(os, environment, root);
  }

  private ExecutableFinder finderWithoutPath(final Os os) {
    final Map<String, String> environment = Map.of();
    final Path root = this.emptyRoot();
    return new ExecutableFinder(os, environment, root);
  }

  private String pathOf(final Path... folders) {
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

  private Optional<Path> absolute(final Path program) {
    final Path absolutePath = program.toAbsolutePath();
    return Optional.of(absolutePath);
  }

  @Test
  void shouldFindProgramsInThePathDirectories() throws IOException {
    final Path first = this.directory.resolve(FIRST_FOLDER);
    Files.createDirectories(first);
    final Path program = this.createProgram(SECOND_FOLDER, X86_64_COMMAND);
    final Path second = program.getParent();
    final String path = this.pathOf(first, second);
    final ExecutableFinder finder = this.finder(Os.LINUX, path);
    final Optional<Path> found = finder.find(X86_64_COMMAND);
    final Optional<Path> expected = this.absolute(program);
    assertEquals(expected, found);
  }

  @Test
  void shouldPreferTheFirstPathDirectory() throws IOException {
    final Path early = this.createProgram(EARLY_FOLDER, TOOL);
    final Path late = this.createProgram(LATE_FOLDER, TOOL);
    final Path earlyFolder = early.getParent();
    final Path lateFolder = late.getParent();
    final String path = this.pathOf(earlyFolder, lateFolder);
    final ExecutableFinder finder = this.finder(Os.LINUX, path);
    final Optional<Path> found = finder.find(TOOL);
    final Optional<Path> expected = this.absolute(early);
    assertEquals(expected, found);
  }

  @Test
  void shouldAddTheExecutableExtensionsOnWindows() throws IOException {
    final Path executable = this.createProgram(EXECUTABLE_FOLDER, QEMU_EXECUTABLE);
    final Path batch = this.createProgram(BATCH_FOLDER, BATCH_FILE);
    final Path command = this.createProgram(COMMAND_FOLDER, COMMAND_FILE);
    final Path executableFolder = executable.getParent();
    final Path batchFolder = batch.getParent();
    final Path commandFolder = command.getParent();
    final String path = this.pathOf(executableFolder, batchFolder, commandFolder);
    final ExecutableFinder windows = this.finder(Os.WINDOWS, path);
    final ExecutableFinder linux = this.finder(Os.LINUX, path);
    final Optional<Path> windowsExecutable = windows.find(QEMU);
    final Optional<Path> windowsBatch = windows.find(BATCH_NAME);
    final Optional<Path> windowsCommand = windows.find(COMMAND_NAME);
    final Optional<Path> linuxExecutable = linux.find(QEMU);
    final Optional<Path> expectedExecutable = this.absolute(executable);
    final Optional<Path> expectedBatch = this.absolute(batch);
    final Optional<Path> expectedCommand = this.absolute(command);
    final boolean linuxExecutableIsEmpty = linuxExecutable.isEmpty();
    assertEquals(expectedExecutable, windowsExecutable);
    assertEquals(expectedBatch, windowsBatch);
    assertEquals(expectedCommand, windowsCommand);
    assertTrue(linuxExecutableIsEmpty);
  }

  @Test
  void shouldPreferTheExtensionsInTheOrderWindowsRunsThem() throws IOException {
    final Path com = this.createProgram(BOTH_FOLDER, TOOL_COM_PROGRAM);
    this.createProgram(BOTH_FOLDER, TOOL_EXECUTABLE);
    final Path folder = com.getParent();
    final String path = this.pathOf(folder);
    final ExecutableFinder windows = this.finder(Os.WINDOWS, path);
    final Optional<Path> found = windows.find(TOOL);
    final Optional<Path> expected = this.absolute(com);
    assertEquals(expected, found);
  }

  @Test
  void shouldFindOnlyFilesWithAnExecutableExtensionOnWindows() throws IOException {
    final Path bare = this.createProgram(PLANTED_FOLDER, QEMU);
    this.createProgram(PLANTED_FOLDER, QEMU_TEXT_FILE);
    final Path named = this.createProgram(PLANTED_FOLDER, UPPER_CASE_EXECUTABLE);
    final Path folder = bare.getParent();
    final String path = this.pathOf(folder);
    final ExecutableFinder windows = this.finder(Os.WINDOWS, path);
    final Optional<Path> bareName = windows.find(QEMU);
    final Optional<Path> fullName = windows.find(UPPER_CASE_EXECUTABLE);
    final boolean bareIsEmpty = bareName.isEmpty();
    final Optional<Path> expectedFull = this.absolute(named);
    assertTrue(bareIsEmpty, bareName::toString);
    assertEquals(expectedFull, fullName);
  }

  @Test
  void shouldReadThePathVariableRegardlessOfItsCase() throws IOException {
    final Path program = this.createProgram(MIXED_FOLDER, TOOL_EXECUTABLE);
    final Path folder = program.getParent();
    final String path = this.pathOf(folder);
    final Map<String, String> environment = Map.of(HOME_VARIABLE, HOME_FOLDER, "Path", path);
    final Path root = this.emptyRoot();
    final ExecutableFinder finder = new ExecutableFinder(Os.WINDOWS, environment, root);
    final Optional<Path> found = finder.find(TOOL);
    final Optional<Path> expected = this.absolute(program);
    assertEquals(expected, found);
  }

  @Test
  void shouldFindNothingWithoutAPathVariable() {
    final Map<String, String> environment = Map.of(HOME_VARIABLE, HOME_FOLDER);
    final Path root = this.emptyRoot();
    final ExecutableFinder finder = new ExecutableFinder(Os.LINUX, environment, root);
    final Optional<Path> found = finder.find(X86_64_COMMAND);
    final boolean foundIsEmpty = found.isEmpty();
    assertTrue(foundIsEmpty);
  }

  @Test
  void shouldSkipBlankAndBrokenPathEntries() throws IOException {
    final Path program = this.createProgram(VALID_FOLDER, TOOL);
    final Path folder = program.getParent();
    final String validFolder = folder.toString();
    final String path = String.join(File.pathSeparator, "", "  ", BROKEN_FOLDER, validFolder);
    final ExecutableFinder finder = this.finder(Os.LINUX, path);
    final Optional<Path> found = finder.find(TOOL);
    final Optional<Path> expected = this.absolute(program);
    assertEquals(expected, found);
  }

  @Test
  void shouldIgnoreFilesThatMayNotBeExecuted() throws IOException {
    final Path program = this.createProgram(PLAIN_FOLDER, NOTES_FILE);
    this.denyExecution(program);
    final boolean executable = Files.isExecutable(program);
    assertFalse(executable, "the test needs a file without execute permission");

    final Path folder = program.getParent();
    final String path = this.pathOf(folder);
    final ExecutableFinder finder = this.finder(Os.LINUX, path);
    final Optional<Path> found = finder.find(NOTES_FILE);
    final boolean foundIsEmpty = found.isEmpty();
    assertTrue(foundIsEmpty);
  }

  private void denyExecution(final Path file) throws IOException {
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

  @Test
  void shouldIgnoreDirectoriesWithTheNameOfTheProgram() throws IOException {
    final Path folder = this.directory.resolve(BIN_FOLDER);
    final Path lookalike = folder.resolve(TOOL);
    Files.createDirectories(lookalike);
    final String path = this.pathOf(folder);
    final ExecutableFinder finder = this.finder(Os.LINUX, path);
    final Optional<Path> found = finder.find(TOOL);
    final boolean foundIsEmpty = found.isEmpty();
    assertTrue(foundIsEmpty);
  }

  @Test
  void shouldLookUpNamesWithAFolderInThatFolderAndReturnAbsolutePaths() throws IOException {
    final Path program = this.createProgram(DIRECT_FOLDER, QEMU);
    final String absoluteName = program.toString();
    final Path workingDirectory = this.workingDirectory();
    final Path relativeProgram = workingDirectory.relativize(program);
    final String relativeName = relativeProgram.toString();
    final ExecutableFinder finder = this.finderWithoutPath(Os.LINUX);
    final Optional<Path> fromAbsolute = finder.find(absoluteName);
    final Optional<Path> fromRelative = finder.find(relativeName);
    final Optional<Path> expected = this.absolute(program);
    final Path found = fromRelative.orElseThrow();
    final boolean isAbsolute = found.isAbsolute();
    final boolean sameFile = Files.isSameFile(program, found);
    assertEquals(expected, fromAbsolute);
    assertTrue(isAbsolute, found::toString);
    assertTrue(sameFile);
  }

  @Test
  void shouldAddExtensionsToPathsOnWindows() throws IOException {
    final Path program = this.createProgram(DIRECT_FOLDER, QEMU_EXECUTABLE);
    final Path folder = program.getParent();
    final Path withoutExtension = folder.resolve(QEMU);
    final String name = withoutExtension.toString();
    final ExecutableFinder finder = this.finderWithoutPath(Os.WINDOWS);
    final Optional<Path> found = finder.find(name);
    final Optional<Path> expected = this.absolute(program);
    assertEquals(expected, found);
  }

  @Test
  void shouldNeverSearchTheWorkingDirectory() throws IOException {
    final Path workingDirectory = this.workingDirectory();
    final Path planted = workingDirectory.resolve(PLANTED_EXECUTABLE);
    Files.writeString(planted, "planted");
    try {
      this.makeExecutable(planted);
      final ExecutableFinder windows = this.finderWithoutPath(Os.WINDOWS);
      final ExecutableFinder linux = this.finderWithoutPath(Os.LINUX);
      final Optional<Path> onWindows = windows.find(PLANTED_PROGRAM);
      final Optional<Path> onLinux = linux.find(PLANTED_EXECUTABLE);
      final boolean windowsIsEmpty = onWindows.isEmpty();
      final boolean linuxIsEmpty = onLinux.isEmpty();
      assertTrue(windowsIsEmpty, onWindows::toString);
      assertTrue(linuxIsEmpty, onLinux::toString);
    } finally {
      Files.deleteIfExists(planted);
    }
  }

  @Test
  void shouldSearchTheFoldersMacOsAddsToLoginShells() throws IOException {
    final Path listed = this.createProgram(LISTED_FOLDER, AARCH64_COMMAND);
    final Path fromPathsD = this.createProgram(PATHS_D_PROGRAM_FOLDER, IMAGE_COMMAND);
    final Path listedFolder = listed.getParent();
    final Path pathsDFolder = fromPathsD.getParent();
    final Path root = this.createMacRoot(listedFolder, pathsDFolder);

    final Map<String, String> environment = Map.of(PATH_VARIABLE, "");
    final ExecutableFinder mac = new ExecutableFinder(Os.MAC, environment, root);
    final ExecutableFinder linux = new ExecutableFinder(Os.LINUX, environment, root);
    final Optional<Path> listedOnMac = mac.find(AARCH64_COMMAND);
    final Optional<Path> pathsDOnMac = mac.find(IMAGE_COMMAND);
    final Optional<Path> listedOnLinux = linux.find(AARCH64_COMMAND);
    final Optional<Path> expectedListed = this.absolute(listed);
    final Optional<Path> expectedPathsD = this.absolute(fromPathsD);
    final boolean linuxIsEmpty = listedOnLinux.isEmpty();
    assertEquals(expectedListed, listedOnMac);
    assertEquals(expectedPathsD, pathsDOnMac);
    assertTrue(linuxIsEmpty);
  }

  private Path createMacRoot(final Path listedFolder, final Path pathsDFolder) throws IOException {
    final Path root = this.directory.resolve(MAC_ROOT_FOLDER);
    final Path etc = root.resolve(ETC_FOLDER);
    final Path pathsD = etc.resolve(PATHS_D_FOLDER);
    Files.createDirectories(pathsD);

    final String listedName = listedFolder.toString();
    final Path pathsFile = etc.resolve(PATHS_FILE);
    this.writeLines(pathsFile, "", listedName, "   ");

    final String pathsDName = pathsDFolder.toString();
    final Path pathsDFile = pathsD.resolve(QEMU_PATHS_FILE);
    this.writeLines(pathsDFile, pathsDName);

    final Path nested = pathsD.resolve(IGNORED_FOLDER);
    Files.createDirectories(nested);
    return root;
  }

  @Test
  void shouldReadThePathsFilesOfMacOsInNameOrder() throws IOException {
    final Path first = this.createProgram(FIRST_FOLDER, TOOL);
    final Path second = this.createProgram(SECOND_FOLDER, TOOL);
    final Path root = this.directory.resolve(MAC_ROOT_FOLDER);
    final Path pathsD = root.resolve(ETC_PATHS_D_FOLDER);
    Files.createDirectories(pathsD);
    final Path firstFolder = first.getParent();
    final Path secondFolder = second.getParent();
    final String firstName = firstFolder.toString();
    final String secondName = secondFolder.toString();
    final Path secondFile = pathsD.resolve(SECOND_PATHS_FILE);
    final Path firstFile = pathsD.resolve(FIRST_PATHS_FILE);
    this.writeLines(secondFile, secondName);
    this.writeLines(firstFile, firstName);
    final Map<String, String> environment = Map.of(PATH_VARIABLE, "");
    final ExecutableFinder mac = new ExecutableFinder(Os.MAC, environment, root);
    final Optional<Path> found = mac.find(TOOL);
    final Optional<Path> expected = this.absolute(first);
    assertEquals(expected, found);
  }

  @Test
  void shouldSearchTheInstallFoldersOfHomebrewOnMacOs() throws IOException {
    final Path root = this.directory.resolve(MAC_ROOT_FOLDER);
    final Path appleSilicon = root.resolve(HOMEBREW_APPLE_SILICON_FOLDER);
    final Path intel = root.resolve(USR_LOCAL_BIN_FOLDER);
    final Path arm = this.writeProgram(appleSilicon, AARCH64_COMMAND);
    final Path x86 = this.writeProgram(intel, X86_64_COMMAND);

    final Map<String, String> environment = Map.of(PATH_VARIABLE, "");
    final ExecutableFinder mac = new ExecutableFinder(Os.MAC, environment, root);
    final Optional<Path> armFound = mac.find(AARCH64_COMMAND);
    final Optional<Path> x86Found = mac.find(X86_64_COMMAND);
    final Optional<Path> expectedArm = this.absolute(arm);
    final Optional<Path> expectedX86 = this.absolute(x86);
    assertEquals(expectedArm, armFound);
    assertEquals(expectedX86, x86Found);
  }

  @Test
  void shouldSearchTheSystemFoldersOnLinuxAndFreeBsd() throws IOException {
    final Path root = this.directory.resolve(UNIX_ROOT_FOLDER);
    final Path usrBin = root.resolve(USR_BIN_FOLDER);
    final Path usrLocalBin = root.resolve(USR_LOCAL_BIN_FOLDER);
    final Path system = this.writeProgram(usrBin, X86_64_COMMAND);
    final Path local = this.writeProgram(usrLocalBin, RISCV64_COMMAND);

    final Map<String, String> environment = Map.of(PATH_VARIABLE, "");
    final ExecutableFinder linux = new ExecutableFinder(Os.LINUX, environment, root);
    final ExecutableFinder freeBsd = new ExecutableFinder(Os.FREEBSD, environment, root);
    final ExecutableFinder mac = new ExecutableFinder(Os.MAC, environment, root);
    final Optional<Path> onLinux = linux.find(X86_64_COMMAND);
    final Optional<Path> localOnFreeBsd = freeBsd.find(RISCV64_COMMAND);
    final Optional<Path> systemOnMac = mac.find(X86_64_COMMAND);
    final Optional<Path> expectedSystem = this.absolute(system);
    final Optional<Path> expectedLocal = this.absolute(local);
    final boolean macIsEmpty = systemOnMac.isEmpty();
    assertEquals(expectedSystem, onLinux);
    assertEquals(expectedLocal, localOnFreeBsd);
    assertTrue(macIsEmpty, "macOS lists /usr/bin in /etc/paths instead");
  }

  @Test
  void shouldSearchOnlyThePathOnAnUnknownOperatingSystem() throws IOException {
    final Path root = this.directory.resolve(UNKNOWN_ROOT_FOLDER);
    final Path usrBin = root.resolve(USR_BIN_FOLDER);
    this.writeProgram(usrBin, X86_64_COMMAND);
    final Path onPath = this.createProgram(ON_PATH_FOLDER, ARM_COMMAND);
    final Path pathFolder = onPath.getParent();
    final String path = this.pathOf(pathFolder);

    final Map<String, String> environment = Map.of(PATH_VARIABLE, path);
    final ExecutableFinder other = new ExecutableFinder(Os.OTHER, environment, root);
    final Optional<Path> inSystemFolder = other.find(X86_64_COMMAND);
    final Optional<Path> inPath = other.find(ARM_COMMAND);

    final boolean systemFolderIsEmpty = inSystemFolder.isEmpty();
    final Optional<Path> expected = this.absolute(onPath);
    assertTrue(systemFolderIsEmpty, inSystemFolder::toString);
    assertEquals(expected, inPath);
  }

  @Test
  void shouldSearchTheQemuFolderOfProgramFilesOnWindows() throws IOException {
    final Path program = this.createProgram(PROGRAM_FILES_QEMU_FOLDER, X86_64_EXECUTABLE);
    final Path qemuFolder = program.getParent();
    final Path programFiles = qemuFolder.getParent();
    final String programFilesName = programFiles.toString();
    final Map<String, String> environment = Map.of(PATH_VARIABLE, "", "ProgramFiles", programFilesName);
    final Path root = this.emptyRoot();
    final ExecutableFinder windows = new ExecutableFinder(Os.WINDOWS, environment, root);
    final Optional<Path> found = windows.find(X86_64_COMMAND);
    final Optional<Path> expected = this.absolute(program);
    assertEquals(expected, found);
  }

  @Test
  void shouldSearchTheDefaultProgramFilesFolderWithoutItsVariable() throws IOException {
    final Path program = this.createProgram(DEFAULT_PROGRAM_FILES_QEMU_FOLDER, X86_64_EXECUTABLE);
    final Path qemu = program.getParent();
    final Optional<Path> expected = this.absolute(program);
    final ExecutableFinder windows = this.finderWithoutPath(Os.WINDOWS);
    try (final MockedStatic<Path> paths = Mockito.mockStatic(Path.class, Mockito.CALLS_REAL_METHODS)) {
      paths.when(() -> assertNotNull(Path.of(DEFAULT_PROGRAM_FILES, QEMU))).thenReturn(qemu);
      final Optional<Path> found = windows.find(X86_64_COMMAND);
      assertEquals(expected, found, "the default Program Files folder is searched without the variable");
    }
  }

  @Test
  void shouldIgnoreAMissingOrUnreadablePathsFile() throws IOException {
    final ExecutableFinder missing = this.finder(Os.MAC, "");
    final Path root = this.directory.resolve(BROKEN_ROOT_FOLDER);
    final Path etc = root.resolve(ETC_FOLDER);
    Files.createDirectories(etc);
    final Path invalid = etc.resolve(PATHS_FILE);
    Files.write(invalid, new byte[] { (byte) 0xC3, (byte) 0x28, '\n' });
    final Map<String, String> environment = Map.of(PATH_VARIABLE, "");
    final ExecutableFinder unreadable = new ExecutableFinder(Os.MAC, environment, root);
    final Optional<Path> fromMissing = missing.find(TOOL);
    final Optional<Path> fromUnreadable = unreadable.find(TOOL);
    final boolean fromMissingIsEmpty = fromMissing.isEmpty();
    final boolean fromUnreadableIsEmpty = fromUnreadable.isEmpty();
    assertTrue(fromMissingIsEmpty);
    assertTrue(fromUnreadableIsEmpty);
  }

  @Test
  void shouldFindProgramsWithTheRealEnvironment() {
    final ExecutableFinder finder = new ExecutableFinder();
    final Os os = OsUtils.getOs();
    final String shell = os == Os.WINDOWS ? WINDOWS_SHELL : UNIX_SHELL;
    final Optional<Path> found = finder.find(shell);
    final Optional<Path> missing = finder.find(MISSING_PROGRAM);
    final boolean foundIsPresent = found.isPresent();
    final boolean missingIsEmpty = missing.isEmpty();
    assertTrue(foundIsPresent, shell);
    assertTrue(missingIsEmpty);
  }

  @Test
  void shouldRejectInvalidNames() {
    final ExecutableFinder finder = this.finderWithoutPath(Os.LINUX);
    assertThrows(NullPointerException.class, () -> finder.find(null));

    final IllegalArgumentException blank = assertThrows(IllegalArgumentException.class, () -> finder.find(" "));
    final String message = blank.getMessage();
    assertEquals("Name must not be blank!", message);
  }
}
