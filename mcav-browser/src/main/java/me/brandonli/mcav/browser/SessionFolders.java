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

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Records the server of each session folder, so a later start removes the folders a killed server left behind.
// Folders of other users stay: in a shared temporary folder, another user could swap their contents for a link.
final class SessionFolders {

  static final String PREFIX = "mcavb-";

  // short: the sockets in the folder have paths of at most 107 characters
  static final int RANDOM_CHARACTERS = 8;

  private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  static final String OWNER_FILE = "server";

  private static final Logger LOGGER = LoggerFactory.getLogger(SessionFolders.class);

  private static final String NOT_RECORDED = "The folder of the browser session {} names no server; if this server is killed, it stays.";

  private static final String RECORD_SEPARATOR = "\n";
  private static final int RECORD_LINES = 2;

  private static final String ANY_NAME = "*";

  private static final String POSIX_ATTRIBUTE_VIEW = "posix";

  private final ProcessHandle owner;
  private final LongFunction<Optional<ProcessHandle>> lookup;

  static SessionFolders ofThisServer() {
    return new SessionFolders(ProcessHandle.current(), ProcessHandle::of);
  }

  @VisibleForTesting
  SessionFolders(final ProcessHandle owner, final LongFunction<Optional<ProcessHandle>> lookup) {
    this.owner = owner;
    this.lookup = lookup;
  }

  static String newName() {
    final StringBuilder name = new StringBuilder(PREFIX);
    for (int index = 0; index < RANDOM_CHARACTERS; index++) {
      name.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }

    return name.toString();
  }

  // a server of unknown start could not be told apart from a later process with its id
  void record(final Path folder) {
    final ProcessHandle.Info information = this.owner.info();
    final @Nullable Instant started = information.startInstant().orElse(null);
    if (started == null) {
      LOGGER.debug(NOT_RECORDED, folder);
      return;
    }

    final String record = this.owner.pid() + RECORD_SEPARATOR + started + RECORD_SEPARATOR;
    try {
      Files.writeString(folder.resolve(OWNER_FILE), record, StandardCharsets.US_ASCII);
    } catch (final IOException exception) {
      LOGGER.debug(NOT_RECORDED, folder, exception);
    }
  }

  int removeStale(final Path temporary) {
    int removed = 0;
    try (final DirectoryStream<Path> folders = Files.newDirectoryStream(temporary, PREFIX + ANY_NAME)) {
      removed = this.removeStaleFolders(folders);
    } catch (final IOException exception) {
      LOGGER.warn("The folders of browser sessions in {} could not be listed ({}).", temporary, exception.toString());
    }

    return removed;
  }

  private int removeStaleFolders(final DirectoryStream<Path> folders) {
    int removed = 0;
    for (final Path folder : folders) {
      if (!this.isStale(folder)) {
        continue;
      }
      HelperSession.deleteFolder(folder);
      LOGGER.debug("Removed the folder {} of a browser whose server ended without releasing it.", folder);
      removed++;
    }

    return removed;
  }

  private boolean isStale(final Path folder) {
    final Path record = folder.resolve(OWNER_FILE);
    final List<String> lines;
    try {
      final boolean own = this.isOwnFolder(folder);
      if (!own || !Files.isRegularFile(record)) {
        return false;
      }

      lines = Files.readAllLines(record, StandardCharsets.US_ASCII);
    } catch (final IOException exception) {
      return false;
    }

    if (lines.size() != RECORD_LINES) {
      return false;
    }

    final long processId;
    final Instant started;
    try {
      processId = Long.parseLong(lines.get(0));
      started = Instant.parse(lines.get(1));
    } catch (final NumberFormatException | DateTimeParseException exception) {
      return false;
    }

    final Optional<ProcessHandle> server = this.lookup.apply(processId);
    final Optional<Instant> serverStart = server.map(ProcessHandle::info).flatMap(ProcessHandle.Info::startInstant);
    return !serverStart.equals(Optional.of(started));
  }

  private boolean isOwnFolder(final Path folder) throws IOException {
    final FileSystem fileSystem = folder.getFileSystem();
    final Set<String> views = fileSystem.supportedFileAttributeViews();
    final boolean posix = views.contains(POSIX_ATTRIBUTE_VIEW);
    if (!posix) {
      return true;
    }

    final ProcessHandle.Info information = this.owner.info();
    final Optional<String> user = information.user();
    final UserPrincipal folderOwner = Files.getOwner(folder, LinkOption.NOFOLLOW_LINKS);
    final String folderOwnerName = folderOwner.getName();
    return user.equals(Optional.of(folderOwnerName));
  }
}
