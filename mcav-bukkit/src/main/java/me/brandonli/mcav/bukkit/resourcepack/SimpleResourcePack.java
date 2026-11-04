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
package me.brandonli.mcav.bukkit.resourcepack;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import me.brandonli.mcav.bukkit.BukkitException;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A minimal resource pack builder that packages custom sounds and arbitrary files into a resource pack zip.
 *
 * <p>Only the features MCAV needs are implemented, which keeps the builder free of any dependency on the
 * Adventure version of the server. A typical use looks like this:
 *
 * {@snippet :
 * final String soundKey = "mcav:audio";
 * final String zipName = "pack.zip";
 * final Path zip = Path.of(zipName);
 * final SimpleResourcePack pack = SimpleResourcePack.pack();
 * pack.metadata(packFormat, "MCAV audio");
 * pack.sound(soundKey, oggFile);
 * pack.zip(zip);
 * }
 *
 * <p>Instances are not thread-safe.
 *
 * <p>Paths passed to {@link #sound(String, Path)} and {@link #external(String, Path)} are retained and read at
 * zip time, so keep those files available and stable until writing finishes. {@link #data(String, byte[])} copies
 * its bytes immediately. The builder owns no open file between operations, may produce multiple archives and
 * does not delete source files. A later registration of the same sound key replaces the earlier one.
 *
 * @since 1.0.0
 */
public final class SimpleResourcePack {

  private static final String PATH_NULL_MESSAGE = "Path must not be null!";

  private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

  private static final Pattern FILE_PATTERN = Pattern.compile("[a-zA-Z0-9_./-]+");

  private static final Pattern UNSAFE_SEGMENT_PATTERN = Pattern.compile("(?:^|/)(?:\\.{1,2})?(?:/|$)");

  private static final Pattern RESERVED_PATTERN = Pattern.compile("pack\\.mcmeta|assets/[^/]+/sounds\\.json|assets/[^/]+/sounds/.+\\.ogg");

  private static final Gson GSON = createGson();

  private static final String PACK_METADATA_ENTRY = "pack.mcmeta";

  private static final String TEMPORARY_FILE_PREFIX = "mcav-pack";
  private static final String TEMPORARY_FILE_SUFFIX = ".zip.part";

  private static final String READABLE_PERMISSIONS = "rw-r--r--";

  private static final char NAMESPACE_SEPARATOR = ':';

  private static final String PATH_SEPARATOR = "/";

  private static final String EMPTY_SEGMENT = "//";

  private static final String PARENT_DIRECTORY = "..";

  // fixed, so the same pack zips to the same bytes and hash in every time zone
  private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 2, 1, 0, 0);

  private final Map<String, Map<String, Path>> sounds;
  private final Map<String, Path> files;
  private final Map<String, byte[]> contents;

  private int format;
  private @Nullable String description;

  SimpleResourcePack() {
    this.sounds = new LinkedHashMap<>();
    this.files = new LinkedHashMap<>();
    this.contents = new LinkedHashMap<>();
    this.format = 0;
  }

  /**
   * Creates a new, empty resource pack builder.
   *
   * @return a new {@code SimpleResourcePack}
   * @since 1.0.0
   */
  public static SimpleResourcePack pack() {
    return new SimpleResourcePack();
  }

  private static Gson createGson() {
    final GsonBuilder builder = new GsonBuilder();
    builder.setPrettyPrinting();
    builder.disableHtmlEscaping();
    return builder.create();
  }

  /**
   * Adds an OGG Vorbis sound to the resource pack and registers a sound event with the same key. The sound is
   * marked as streamed, so the client plays long audio directly from disk instead of loading it into memory.
   * Play the sound in-game using its key, for example {@code /playsound mcav:audio master @a}.
   *
   * @param key  the namespaced key of the sound, such as {@code mcav:audio}
   * @param path the path to the OGG Vorbis sound file
   * @throws IllegalArgumentException if the key is invalid, contains a prohibited path segment, or the source
   *                                  is not a regular file; audio encoding itself is not checked
   * @throws NullPointerException     if {@code key} or {@code path} is null
   * @since 1.0.0
   */
  public void sound(final String key, final Path path) {
    Preconditions.checkNotNull(key, "Key must not be null!");
    Preconditions.checkNotNull(path, PATH_NULL_MESSAGE);

    final Matcher keyMatcher = KEY_PATTERN.matcher(key);
    final boolean validKey = keyMatcher.matches();
    Preconditions.checkArgument(validKey, "Sound key %s is invalid!", key);

    final boolean regularFile = Files.isRegularFile(path);
    Preconditions.checkArgument(regularFile, "Sound file %s is not a regular file!", path);

    final int separator = key.indexOf(NAMESPACE_SEPARATOR);
    final String namespace = key.substring(0, separator);
    final String value = key.substring(separator + 1);
    final String soundPath = String.join(PATH_SEPARATOR, namespace, value);
    this.checkPathSegments(soundPath);

    final Map<String, Path> namespaceSounds = this.sounds.computeIfAbsent(namespace, _ -> new LinkedHashMap<>());
    namespaceSounds.put(value, path);
  }

  /**
   * Adds an arbitrary file to the resource pack at the specified location inside the zip.
   *
   * <p>The location is relative to the root of the pack, so it must not start or end with a slash. The entries this
   * builder generates are reserved and cannot be replaced: {@code pack.mcmeta}, {@code assets/<namespace>/sounds.json},
   * and every {@code .ogg} file below {@code assets/<namespace>/sounds/}. Use {@link #sound(String, Path)} to add
   * sounds.
   *
   * @param path the location of the file inside the resource pack, such as {@code assets/mcav/texts/credits.txt}
   * @param file the file to add
   * @throws IllegalArgumentException if the location is invalid or reserved, or the file is not a regular file
   * @throws NullPointerException     if {@code path} or {@code file} is null
   * @since 1.0.0
   */
  public void external(final String path, final Path file) {
    Preconditions.checkNotNull(path, PATH_NULL_MESSAGE);
    Preconditions.checkNotNull(file, "File must not be null!");
    this.checkEntryPath(path);

    final boolean regularFile = Files.isRegularFile(file);
    Preconditions.checkArgument(regularFile, "File %s is not a regular file!", file);

    this.contents.remove(path);
    this.files.put(path, file);
  }

  /**
   * Adds a file with the given content to the resource pack at the specified location inside the zip, for files that
   * are generated rather than read from disk. The same locations are allowed as for {@link #external(String, Path)},
   * and a location given to both methods holds whatever the last call added.
   *
   * @param path    the location of the file inside the resource pack, such as {@code assets/mcav/shaders/post/a.fsh}
   * @param content the content of the file, which is copied
   * @throws IllegalArgumentException if the location is invalid or reserved
   * @throws NullPointerException     if {@code path} or {@code content} is null
   * @since 1.0.0
   */
  public void data(final String path, final byte[] content) {
    Preconditions.checkNotNull(path, PATH_NULL_MESSAGE);
    Preconditions.checkNotNull(content, "Content must not be null!");
    this.checkEntryPath(path);

    this.files.remove(path);
    this.contents.put(path, content.clone());
  }

  private void checkEntryPath(final String path) {
    final Matcher pathMatcher = FILE_PATTERN.matcher(path);
    final boolean validPath = pathMatcher.matches() && !path.contains(PARENT_DIRECTORY);
    Preconditions.checkArgument(validPath, "Pack path %s is invalid!", path);

    final boolean relativeFile = !path.startsWith(PATH_SEPARATOR) && !path.endsWith(PATH_SEPARATOR) && !path.contains(EMPTY_SEGMENT);
    Preconditions.checkArgument(relativeFile, "Pack path %s must be relative to the pack root and name a file!", path);
    this.checkPathSegments(path);

    final Matcher reservedMatcher = RESERVED_PATTERN.matcher(path);
    final boolean reserved = reservedMatcher.matches();
    Preconditions.checkArgument(!reserved, "Pack path %s is reserved for entries generated by the builder!", path);
  }

  private void checkPathSegments(final String path) {
    final Matcher segments = UNSAFE_SEGMENT_PATTERN.matcher(path);
    final boolean unsafe = segments.find();
    Preconditions.checkArgument(!unsafe, "Pack path %s must not contain empty, dot or parent segments!", path);
  }

  /**
   * Sets the {@code pack.mcmeta} information of the resource pack. This must be called before zipping.
   *
   * @param format      the resource pack format of the targeted Minecraft version
   * @param description the description shown in the resource pack menu
   * @throws IllegalArgumentException if the format is not positive
   * @throws NullPointerException     if {@code description} is null
   * @since 1.0.0
   */
  public void metadata(final int format, final String description) {
    Preconditions.checkArgument(format > 0, "Pack format %s must be positive!", format);
    Preconditions.checkNotNull(description, "Description must not be null!");

    this.format = format;
    this.description = description;
  }

  /**
   * Writes the resource pack as a zip to the specified destination. The zip is written to a temporary file first
   * and moved into place afterward, atomically where the file system supports it, so readers never observe a
   * partially written pack. On POSIX file systems the pack is readable by everyone ({@code rw-r--r--}), so a
   * separate web server can serve it.
   *
   * @param destination the destination path of the zipped resource pack
   * @throws IllegalStateException    if {@link #metadata(int, String)} was never called
   * @throws IllegalArgumentException if the destination is a root directory
   * @throws BukkitException          if the pack could not be written
   * @throws NullPointerException     if {@code destination} is null
   * @since 1.0.0
   */
  public void zip(final Path destination) {
    Preconditions.checkNotNull(destination, "Destination must not be null!");

    final String packDescription = this.description;
    if (packDescription == null) {
      final String message = "Pack metadata must be set using metadata(format, description)!";
      throw new IllegalStateException(message);
    }

    final Path absolute = destination.toAbsolutePath();
    final Path parent = absolute.getParent();
    if (parent == null) {
      final String message = "Destination must be a file, not a root directory!";
      throw new IllegalArgumentException(message);
    }

    try {
      this.writeAtomically(parent, absolute, packDescription);
    } catch (final IOException exception) {
      final String message = "Resource pack %s could not be written!".formatted(absolute);
      throw new BukkitException(message, exception);
    }
  }

  private void writeAtomically(final Path parent, final Path target, final String packDescription) throws IOException {
    Files.createDirectories(parent);
    final Path temporaryFile = Files.createTempFile(parent, TEMPORARY_FILE_PREFIX, TEMPORARY_FILE_SUFFIX);
    try {
      final PosixFileAttributeView permissionView = Files.getFileAttributeView(temporaryFile, PosixFileAttributeView.class);
      makeReadable(permissionView);
      this.writeZip(temporaryFile, packDescription);
      moveIntoPlace(temporaryFile, target, Files::move);
    } finally {
      Files.deleteIfExists(temporaryFile);
    }
  }

  // temporary files are owner-only and keep that mode after the move
  @VisibleForTesting
  static void makeReadable(final @Nullable PosixFileAttributeView view) throws IOException {
    if (view == null) {
      return;
    }

    final Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(READABLE_PERMISSIONS);
    view.setPermissions(permissions);
  }

  @VisibleForTesting
  static void moveIntoPlace(final Path source, final Path target, final FileMover mover) throws IOException {
    try {
      mover.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (final AtomicMoveNotSupportedException exception) {
      mover.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void writeZip(final Path target, final String packDescription) throws IOException {
    try (final OutputStream fileOutput = Files.newOutputStream(target); final ZipOutputStream zip = new ZipOutputStream(fileOutput)) {
      final String packMetadata = this.createPackMetadata(packDescription);
      this.writeEntry(zip, PACK_METADATA_ENTRY, packMetadata);
      this.writeSounds(zip);
      for (final Map.Entry<String, Path> entry : this.files.entrySet()) {
        final String name = entry.getKey();
        final Path file = entry.getValue();
        this.writeFile(zip, name, file);
      }
      for (final Map.Entry<String, byte[]> entry : this.contents.entrySet()) {
        final String name = entry.getKey();
        final byte[] content = entry.getValue();
        final ZipEntry zipEntry = createEntry(name);
        zip.putNextEntry(zipEntry);
        zip.write(content);
        zip.closeEntry();
      }
    }
  }

  private String createPackMetadata(final String packDescription) {
    final JsonObject pack = new JsonObject();
    pack.addProperty("description", packDescription);
    pack.addProperty("pack_format", this.format);
    pack.addProperty("min_format", this.format);
    pack.addProperty("max_format", this.format);
    final JsonObject root = new JsonObject();
    root.add("pack", pack);
    return GSON.toJson(root);
  }

  private void writeSounds(final ZipOutputStream zip) throws IOException {
    for (final Map.Entry<String, Map<String, Path>> namespaceEntry : this.sounds.entrySet()) {
      final String namespace = namespaceEntry.getKey();
      final Map<String, Path> namespaceSounds = namespaceEntry.getValue();
      final JsonObject events = new JsonObject();
      for (final Map.Entry<String, Path> soundEntry : namespaceSounds.entrySet()) {
        final String value = soundEntry.getKey();
        final Path file = soundEntry.getValue();
        final JsonObject event = this.createSoundEvent(namespace, value);
        events.add(value, event);
        final String entryName = "assets/%s/sounds/%s.ogg".formatted(namespace, value);
        this.writeFile(zip, entryName, file);
      }
      final String soundsJson = GSON.toJson(events);
      final String soundsEntryName = "assets/%s/sounds.json".formatted(namespace);
      this.writeEntry(zip, soundsEntryName, soundsJson);
    }
  }

  private JsonObject createSoundEvent(final String namespace, final String value) {
    final String soundName = "%s:%s".formatted(namespace, value);
    final JsonObject sound = new JsonObject();
    sound.addProperty("name", soundName);
    sound.addProperty("type", "file");
    sound.addProperty("stream", true);
    final JsonArray soundList = new JsonArray();
    soundList.add(sound);
    final JsonObject event = new JsonObject();
    event.addProperty("replace", false);
    event.add("sounds", soundList);
    return event;
  }

  @VisibleForTesting
  static ZipEntry createEntry(final String name) {
    final ZipEntry entry = new ZipEntry(name);
    entry.setTimeLocal(ENTRY_TIME);
    return entry;
  }

  private void writeEntry(final ZipOutputStream zip, final String name, final String content) throws IOException {
    final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    final ZipEntry zipEntry = createEntry(name);
    zip.putNextEntry(zipEntry);
    zip.write(bytes);
    zip.closeEntry();
  }

  private void writeFile(final ZipOutputStream zip, final String name, final Path file) throws IOException {
    final ZipEntry zipEntry = createEntry(name);
    zip.putNextEntry(zipEntry);
    Files.copy(file, zip);
    zip.closeEntry();
  }

  @FunctionalInterface
  interface FileMover {
    void move(Path source, Path target, CopyOption... options) throws IOException;
  }
}
