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
package me.brandonli.mcav.utils.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.testing.TestMedia;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.MetadataUtils;
import me.brandonli.mcav.utils.runtime.CommandTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link FFmpegTemplates}.
 */
final class FFmpegTemplatesTest {

  @TempDir
  private Path directory;

  @Test
  void extractAudioKeepsOnlyTheAudioTrack() {
    final FFmpegCommand command = FFmpegTemplates.extractAudio("in.mp4", "libopus", "out.opus");
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of("-i", "in.mp4", "-vn", "-strict", "-2", "-c:a", "libopus", "-y", "out.opus");
    assertEquals(expected, arguments);
  }

  @Test
  void extractOggVorbisProducesAPlayableFile() throws IOException {
    final Path video = TestMedia.video();
    final Path output = this.directory.resolve("audio.ogg");
    final String videoPath = video.toString();
    final String outputPath = output.toString();
    final FFmpegCommand command = FFmpegTemplates.extractOggVorbis(videoPath, outputPath);
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of("-i", videoPath, "-vn", "-strict", "-2", "-c:a", "vorbis", "-ac", "2", "-y", outputPath);
    final CommandTask task = command.createTask();
    final int exitCode = task.run();
    final String errorOutput = task.getErrorOutput();
    final long size = Files.size(output);
    assertEquals(expected, arguments);
    assertEquals(0, exitCode, errorOutput);
    assertTrue(size > 1_000, "the file holds encoded audio");
    final FileSource source = FileSource.path(output);
    final OriginalAudioMetadata metadata = MetadataUtils.parseAudioMetadata(source);
    final int channels = metadata.getAudioChannels();
    final String codec = metadata.getAudioCodec();
    final int sampleRate = metadata.getAudioSampleRate();
    assertEquals(2, channels, "the audio is stereo as requested");
    final boolean vorbis = codec.contains("vorbis");
    assertTrue(vorbis, codec);
    assertTrue(sampleRate > 0, "the file can be decoded");
  }

  @Test
  void compressVideoSetsBothBitrates() {
    final FFmpegCommand command = FFmpegTemplates.compressVideo("in.mp4", "out.mp4", "1M", "96k");
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of("-i", "in.mp4", "-c:v", "libx264", "-b:v", "1M", "-c:a", "aac", "-b:a", "96k", "-y", "out.mp4");
    assertEquals(expected, arguments);
  }

  @Test
  void extractClipSeeksBeforeTheInput() {
    final FFmpegCommand command = FFmpegTemplates.extractClip("in.mp4", "out.mp4", "00:00:10", "5");
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of("-ss", "00:00:10", "-i", "in.mp4", "-t", "5", "-c:v", "copy", "-c:a", "copy", "-y", "out.mp4");
    assertEquals(expected, arguments);
  }

  @Test
  void createThumbnailWritesOneFrame() {
    final FFmpegCommand command = FFmpegTemplates.createThumbnail("in.mp4", "thumb.png", "3");
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of("-ss", "3", "-i", "in.mp4", "-frames:v", "1", "-y", "thumb.png");
    assertEquals(expected, arguments);
  }

  @Test
  void remuxVideoCopiesBothStreams() {
    final FFmpegCommand command = FFmpegTemplates.remuxVideo("in.mkv", "out.mp4");
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of("-i", "in.mkv", "-c:v", "copy", "-c:a", "copy", "-y", "out.mp4");
    assertEquals(expected, arguments);
  }

  @Test
  void rejectsMissingArguments() {
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.extractAudio(null, "aac", "o"));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.extractAudio("i", null, "o"));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.extractAudio("i", "aac", null));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.extractOggVorbis(null, "o"));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.extractOggVorbis("i", null));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.compressVideo(null, "o", "1M", "1k"));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.compressVideo("i", null, "1M", "1k"));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.compressVideo("i", "o", null, "1k"));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.compressVideo("i", "o", "1M", null));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.extractClip(null, "o", "0", "1"));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.extractClip("i", null, "0", "1"));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.extractClip("i", "o", null, "1"));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.extractClip("i", "o", "0", null));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.createThumbnail(null, "o", "0"));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.createThumbnail("i", null, "0"));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.createThumbnail("i", "o", null));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.remuxVideo(null, "o"));
    assertThrows(NullPointerException.class, () -> FFmpegTemplates.remuxVideo("i", null));
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(FFmpegTemplates.class);
  }
}
