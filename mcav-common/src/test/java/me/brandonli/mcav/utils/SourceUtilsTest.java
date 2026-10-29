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
package me.brandonli.mcav.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.CapabilityGuard;
import me.brandonli.mcav.capability.installer.FileMode;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.ytdlp.YtdlpInstaller;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.YtdlpParser;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.device.DeviceSource;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class SourceUtilsTest {

  private static final String UPPER_CASE_GIF_PATH = "images/ANIMATION.GIF";
  private static final String DOUBLE_EXTENSION_PATH = "images/animation.gif.mp4";
  private static final String GIF_DIRECTORY_PATH = "images.gif/animation";
  private static final String BACKSLASH_GIF_DIRECTORY_PATH = "images.gif\\animation";
  private static final String TRAILING_DOT_PATH = "animation.";
  private static final String BARE_GIF_NAME = "gif";
  private static final String DRIVE_PATH = "c:/videos/clip.mp4";

  private static final String EXISTING_FILE_NAME = "exists.txt";
  private static final String MISSING_FILE_NAME = "missing.txt";
  private static final String INVALID_PATH = "bad\u0000path";
  private static final String SCRIPT_FILE_NAME = "yt-dlp";
  private static final String WINDOWS_SCRIPT_FILE_NAME = "yt-dlp.cmd";

  private static final String LIVE_URL = "https://example.com/live";
  private static final String INVALID_URL = "http://[";

  @TempDir
  private Path directory;

  private static boolean isGif(final String path) {
    final Path file = Path.of(path);
    final FileSource source = FileSource.path(file);
    return SourceUtils.isImageGif(source);
  }

  @Test
  void shouldRecognizeGifFilesAndUris() {
    final URI gifUri = URI.create("https://example.com/animation.gif?size=large");
    final URI opaqueUri = URI.create("mailto:someone@example.com");
    final UriSource gifSource = UriSource.uri(gifUri);
    final UriSource opaqueSource = UriSource.uri(opaqueUri);
    final Source device = DeviceSource.device(0);
    final boolean upperCase = isGif(UPPER_CASE_GIF_PATH);
    final boolean doubleExtension = isGif(DOUBLE_EXTENSION_PATH);
    final boolean dotInDirectory = isGif(GIF_DIRECTORY_PATH);
    final boolean trailingDot = isGif(TRAILING_DOT_PATH);
    final boolean backslashDirectory = isGif(BACKSLASH_GIF_DIRECTORY_PATH);
    final boolean gifUriDetected = SourceUtils.isImageGif(gifSource);
    final boolean opaqueDetected = SourceUtils.isImageGif(opaqueSource);
    final boolean deviceDetected = SourceUtils.isImageGif(device);
    assertTrue(upperCase);
    assertFalse(doubleExtension);
    assertFalse(dotInDirectory);
    assertFalse(trailingDot);
    assertFalse(backslashDirectory);
    assertTrue(gifUriDetected);
    assertFalse(opaqueDetected);
    assertFalse(deviceDetected);
    assertThrows(NullPointerException.class, () -> SourceUtils.isImageGif(null));
  }

  @Test
  void shouldNotTakeANameWithoutADotForAnExtension() {
    final boolean bareName = isGif(BARE_GIF_NAME);
    assertFalse(bareName);
  }

  @Test
  void shouldRecognizeUrisWithSchemeAndHost() {
    final boolean isWebUri = SourceUtils.isUri("https://example.com/video");
    final boolean stream = SourceUtils.isUri("rtsp://camera.local/live");
    final boolean isDrivePath = SourceUtils.isUri(DRIVE_PATH);
    final boolean invalid = SourceUtils.isUri(INVALID_URL);
    assertTrue(isWebUri);
    assertTrue(stream);
    assertFalse(isDrivePath);
    assertFalse(invalid);
    assertThrows(NullPointerException.class, () -> SourceUtils.isUri(null));
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "https://example.com/clip.MP4",
      "https://example.com/live/stream.m3u8",
      "http://x.org/a/b/song.flac?x=1",
      "https://example.com/audio.mka",
      "https://example.com/phone.3gp",
      "https://example.com/bluray.m2ts",
      "https://example.com/camera.mts",
      "https://example.com/picture.avif",
      "https://example.com/dvd.vob",
    }
  )
  void shouldRecognizeDirectMediaLinks(final String url) {
    final boolean direct = SourceUtils.isDirectVideo(url);
    assertTrue(direct);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
    strings = { "", "https://example.com", "https://example.com/watch.php", "https://example.com/page", "mailto:a@b.c", "http://[" }
  )
  void shouldRejectLinksThatAreNotMediaFiles(final @Nullable String url) {
    final boolean direct = SourceUtils.isDirectVideo(url);
    assertFalse(direct);
  }

  @Test
  void shouldReadAnExtensionOnlyAfterTheLastSeparator() {
    final boolean wholeWord = SourceUtils.isDirectVideo("mp4");
    final boolean directoryDot = SourceUtils.isDirectVideo("https://example.com/video.files/clip");
    final boolean trailingDot = SourceUtils.isDirectVideo("https://example.com/clip.");
    assertFalse(wholeWord, "a path without a dot has no extension, even when it reads like one");
    assertFalse(directoryDot, "a dot in a directory name is not the extension of the file");
    assertFalse(trailingDot, "a trailing dot leaves no extension behind it");
  }

  @Test
  void shouldRecognizeExistingPaths() throws IOException {
    final Path file = this.directory.resolve(EXISTING_FILE_NAME);
    Files.createFile(file);
    final String existingRaw = file.toString();
    final Path missingFile = this.directory.resolve(MISSING_FILE_NAME);
    final String missingRaw = missingFile.toString();
    final boolean existing = SourceUtils.isPath(existingRaw);
    final boolean missing = SourceUtils.isPath(missingRaw);
    final boolean invalid = SourceUtils.isPath(INVALID_PATH);
    assertTrue(existing);
    assertFalse(missing);
    assertFalse(invalid);
    assertThrows(NullPointerException.class, () -> SourceUtils.isPath(null));
  }

  @Test
  void shouldAskTheParserWhetherAStreamIsLive() {
    final UrlParseDump live = parseDump("{\"id\": \"live\", \"is_live\": true}");
    final UrlParseDump recorded = parseDump("{\"id\": \"recorded\", \"is_live\": false}");
    final List<URI> asked = new ArrayList<>();
    final YtdlpParser liveParser = (input, _) -> this.recordAskedUri(input, asked, live);
    final YtdlpParser recordedParser = (_, _) -> recorded;

    final boolean liveResult = SourceUtils.isDynamicStream(LIVE_URL, liveParser);
    final boolean recordedResult = SourceUtils.isDynamicStream("https://example.com/recorded", recordedParser);
    final boolean invalidResult = SourceUtils.isDynamicStream(INVALID_URL, liveParser);

    final URI expectedUri = URI.create(LIVE_URL);
    final List<URI> expectedAsked = List.of(expectedUri);
    assertTrue(liveResult);
    assertFalse(recordedResult);
    assertFalse(invalidResult);
    assertEquals(expectedAsked, asked, "an invalid URL is never handed to the parser");
  }

  private UrlParseDump recordAskedUri(final UriSource input, final List<URI> asked, final UrlParseDump answer) {
    final URI uri = input.getUri();
    asked.add(uri);

    return answer;
  }

  @Test
  void shouldReportNoLiveStreamWhenTheParserFails() {
    final YtdlpParser offlineParser = (_, _) -> this.failOffline();
    final YtdlpParser rejectingParser = (_, _) -> this.rejectUrl();

    final boolean offlineResult = SourceUtils.isDynamicStream(LIVE_URL, offlineParser);
    final boolean rejectedResult = SourceUtils.isDynamicStream("ftp://example.com/live", rejectingParser);

    assertFalse(offlineResult, "a failing lookup is not a live stream");
    assertFalse(rejectedResult, "a URL the parser rejects is not a live stream");
  }

  private UrlParseDump failOffline() {
    throw new InstallationException("offline!");
  }

  private UrlParseDump rejectUrl() {
    throw new IllegalArgumentException("not a web URL!");
  }

  private static UrlParseDump parseDump(final String json) {
    final Gson gson = GsonProvider.getSimple();
    return gson.fromJson(json, UrlParseDump.class);
  }

  @Test
  void shouldAskTheSharedYtdlpInstallationWithoutTouchingTheNetwork() throws IOException {
    final YtdlpInstaller installer = Mockito.mock(YtdlpInstaller.class);
    final InstallationException offline = new InstallationException("offline!");
    Mockito.when(installer.download(FileMode.EXECUTABLE)).thenThrow(offline);
    try (final MockedStatic<YtdlpInstaller> installers = Mockito.mockStatic(YtdlpInstaller.class)) {
      installers.when(YtdlpInstaller::shared).thenReturn(installer);
      final boolean live = SourceUtils.isDynamicStream(LIVE_URL);
      assertFalse(live, "yt-dlp that cannot be installed reports no live stream");
    }

    final YtdlpInstaller verifiedInstaller = Mockito.verify(installer);
    verifiedInstaller.download(FileMode.EXECUTABLE);
    assertThrows(NullPointerException.class, () -> SourceUtils.isDynamicStream(null));
  }

  @Test
  void shouldRefuseALiveCheckWhileTheLibraryPreparesYtdlp() {
    final YtdlpInstaller installer = Mockito.mock(YtdlpInstaller.class);
    final CapabilityGuard guard = CapabilityGuard.shared();
    guard.markPreparing(Capability.YT_DLP);
    try (final MockedStatic<YtdlpInstaller> installers = Mockito.mockStatic(YtdlpInstaller.class)) {
      installers.when(YtdlpInstaller::shared).thenReturn(installer);
      assertThrows(IllegalStateException.class, () -> SourceUtils.isDynamicStream(LIVE_URL));
    } finally {
      guard.forget(Capability.YT_DLP);
    }

    Mockito.verifyNoInteractions(installer);
  }

  @Test
  void shouldReportALiveStreamThatTheSharedYtdlpInstallationFinds() throws IOException {
    final Path script = this.writeLiveYtdlpScript();
    final YtdlpInstaller installer = Mockito.mock(YtdlpInstaller.class);
    Mockito.when(installer.download(FileMode.EXECUTABLE)).thenReturn(script);
    try (final MockedStatic<YtdlpInstaller> installers = Mockito.mockStatic(YtdlpInstaller.class)) {
      installers.when(YtdlpInstaller::shared).thenReturn(installer);

      final boolean live = SourceUtils.isDynamicStream(LIVE_URL);
      assertTrue(live);
    }
  }

  private Path writeLiveYtdlpScript() throws IOException {
    final Os operatingSystem = OsUtils.getOs();
    if (operatingSystem == Os.WINDOWS) {
      final Path script = this.directory.resolve(WINDOWS_SCRIPT_FILE_NAME);
      Files.writeString(script, "@echo {\"is_live\": true}\r\n", StandardCharsets.UTF_8);
      return script;
    }

    final Path script = this.directory.resolve(SCRIPT_FILE_NAME);
    Files.writeString(script, "#!/bin/sh\necho '{\"is_live\": true}'\n", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    return script;
  }

  @Test
  void shouldNotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(SourceUtils.class);
  }
}
