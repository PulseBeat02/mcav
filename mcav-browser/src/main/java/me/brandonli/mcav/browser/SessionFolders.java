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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records which server made the folder of a browser session, so that a later start of the browser module removes the
 * folders a killed server left behind.
 *
 * <p>The folder of a session holds the page's profile, its cookies and cache among them; a server that is killed (a
 * SIGKILL, a crash, the out-of-memory killer) cannot remove it. Each folder therefore gets a file with the process id
 * and the start instant of its server, and {@link #removeStale(Path)} removes every folder whose server is gone, while
 * the process with the recorded id is not the one that made it (another start instant, or no process at all). A folder
 * without the file, made by an earlier version or still being made, is left alone.
 */
final class SessionFolders {

  /**
   * The start of the name of every folder of a session.
   */
  static final String PREFIX = "mcav-browser-";

  /**
   * The file in the folder of a session that names its server.
   */
  static final String OWNER_FILE = "server";

  private static final Logger LOGGER = LoggerFactory.getLogger(SessionFolders.class);
  private static final String REMOVED = "Removed the folder {} of a browser whose server ended without releasing it";
  private static final String NOT_RECORDED = "The folder of the browser session {} names no server; if this server is killed, it stays";
  private static final String NOT_LISTED = "The folders of browser sessions in {} could not be listed: {}";

  private final ProcessHandle owner;
  private final LongFunction<Optional<ProcessHandle>> lookup;

  /**
   * Creates the records of this server.
   *
   * @return the records
   */
  static SessionFolders ofThisServer() {
    return new SessionFolders(ProcessHandle.current(), ProcessHandle::of);
  }

  /**
   * Creates records.
   *
   * @param owner  the server that makes folders, this JVM outside of tests
   * @param lookup finds a running process by its id
   */
  @VisibleForTesting
  SessionFolders(final ProcessHandle owner, final LongFunction<Optional<ProcessHandle>> lookup) {
    this.owner = owner;
    this.lookup = lookup;
  }

  /**
   * Names the server in a new folder. A server whose start instant the system does not report could not be told from
   * a later process with its id, so its folders name nothing, and neither does one whose file cannot be written.
   *
   * @param folder the new folder of a session
   */
  void record(final Path folder) {
    final Optional<Instant> started = this.owner.info().startInstant();
    if (started.isEmpty()) {
      LOGGER.debug(NOT_RECORDED, folder);
      return;
    }
    final String record = this.owner.pid() + "\n" + started.get() + "\n";
    try {
      Files.writeString(folder.resolve(OWNER_FILE), record, StandardCharsets.US_ASCII);
    } catch (final IOException exception) {
      LOGGER.debug(NOT_RECORDED, folder, exception);
    }
  }

  /**
   * Removes every folder of a session in a temporary folder whose server is gone.
   *
   * @param temporary the temporary folder
   * @return how many folders were removed
   */
  int removeStale(final Path temporary) {
    int removed = 0;
    try (final DirectoryStream<Path> folders = Files.newDirectoryStream(temporary, PREFIX + "*")) {
      for (final Path folder : folders) {
        if (this.isStale(folder)) {
          HelperSession.deleteFolder(folder);
          LOGGER.info(REMOVED, folder);
          removed++;
        }
      }
    } catch (final IOException exception) {
      LOGGER.warn(NOT_LISTED, temporary, exception.toString());
    }
    return removed;
  }

  // a folder of another user, which cannot be read, or one without a readable record, is left alone
  private boolean isStale(final Path folder) {
    final Path record = folder.resolve(OWNER_FILE);
    if (!Files.isRegularFile(record)) {
      return false;
    }
    final List<String> lines;
    try {
      lines = Files.readAllLines(record, StandardCharsets.US_ASCII);
    } catch (final IOException exception) {
      return false;
    }
    if (lines.size() != 2) {
      return false;
    }
    final long pid;
    final Instant started;
    try {
      pid = Long.parseLong(lines.get(0));
      started = Instant.parse(lines.get(1));
    } catch (final NumberFormatException | DateTimeParseException exception) {
      return false;
    }
    final Optional<ProcessHandle> server = this.lookup.apply(pid);
    return server.isEmpty() || !server.get().info().startInstant().equals(Optional.of(started));
  }
}
