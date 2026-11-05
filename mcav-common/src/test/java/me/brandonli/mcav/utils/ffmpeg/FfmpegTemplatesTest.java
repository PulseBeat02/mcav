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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.testing.TestMedia;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.MetadataUtils;
import me.brandonli.mcav.utils.runtime.CommandTask;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FfmpegTemplatesTest {

  private static final String INPUT_NAME = "in.mp4";
  private static final String MATROSKA_INPUT_NAME = "in.mkv";
  private static final String OUTPUT_NAME = "out.mp4";
  private static final String AUDIO_OUTPUT_NAME = "out.opus";
  private static final String OGG_OUTPUT_NAME = "audio.ogg";
  private static final String THUMBNAIL_NAME = "thumb.png";

  private static final String PLACEHOLDER_INPUT = "i";
  private static final String PLACEHOLDER_OUTPUT = "o";

  private static final String VIDEO_BITRATE = "1M";

  private static final String INPUT_FLAG = "-i";
  private static final String OVERWRITE_FLAG = "-y";
  private static final String NO_VIDEO_FLAG = "-vn";
  private static final String SEEK_FLAG = "-ss";
  private static final String VIDEO_CODEC_FLAG = "-c:v";
  private static final String AUDIO_CODEC_FLAG = "-c:a";
  private static final String STRICTNESS_FLAG = "-strict";
  private static final String EXPERIMENTAL_STRICTNESS = "-2";

  private static final String AAC_CODEC = "aac";
  private static final String COPY_CODEC = "copy";

  @TempDir
  private Path directory;

  @Test
  void shouldKeepOnlyTheAudioTrackWhenExtractingAudio() {
    final FfmpegCommand command = FfmpegTemplates.extractAudio(INPUT_NAME, "libopus", AUDIO_OUTPUT_NAME);
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of(
      INPUT_FLAG,
      INPUT_NAME,
      NO_VIDEO_FLAG,
      STRICTNESS_FLAG,
      EXPERIMENTAL_STRICTNESS,
      AUDIO_CODEC_FLAG,
      "libopus",
      OVERWRITE_FLAG,
      AUDIO_OUTPUT_NAME
    );
    assertEquals(expected, arguments);
  }

  @Test
  void shouldProduceAPlayableFileWhenExtractingOggVorbis() throws IOException {
    final Path video = TestMedia.video();
    final Path output = this.directory.resolve(OGG_OUTPUT_NAME);
    final String videoPath = video.toString();
    final String outputPath = output.toString();
    final FfmpegCommand command = FfmpegTemplates.extractOggVorbis(videoPath, outputPath);
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of(
      INPUT_FLAG,
      videoPath,
      NO_VIDEO_FLAG,
      STRICTNESS_FLAG,
      EXPERIMENTAL_STRICTNESS,
      AUDIO_CODEC_FLAG,
      "vorbis",
      "-ac",
      "2",
      OVERWRITE_FLAG,
      outputPath
    );
    final CommandTask task = command.createTask();
    final int exitCode = task.run();
    final String errorOutput = task.getErrorOutput();
    final long size = Files.size(output);
    assertEquals(expected, arguments);
    assertEquals(0, exitCode, errorOutput);

    final boolean holdsAudio = size > 1_000;
    assertTrue(holdsAudio, "the file holds encoded audio");

    final FileSource source = FileSource.path(output);
    final OriginalAudioMetadata metadata = MetadataUtils.parseAudioMetadata(source);
    final int channels = metadata.getAudioChannels();
    final String codec = metadata.getAudioCodec();
    final int sampleRate = metadata.getAudioSampleRate();
    assertEquals(2, channels, "the audio is stereo as requested");

    final boolean isVorbis = codec.contains("vorbis");
    assertTrue(isVorbis, codec);

    final boolean decodable = sampleRate > 0;
    assertTrue(decodable, "the file can be decoded");

    long decodedSamples = 0;
    try (final FFmpegFrameGrabber decoder = new FFmpegFrameGrabber(output.toFile())) {
      decoder.start();
      Frame frame = decoder.grabSamples();
      while (frame != null) {
        assertNotNull(frame.samples);
        assertEquals(2, frame.audioChannels);
        assertEquals(sampleRate, frame.sampleRate);
        decodedSamples += this.countRemainingSamples(frame.samples);
        frame = decoder.grabSamples();
      }
    }

    final long decodedFrames = decodedSamples / channels;
    final long expectedFrames = (long) TestMedia.VIDEO_SECONDS * sampleRate;
    // AAC and Vorbis may pad the source and destination by a codec block.
    final boolean withinACodecBlock = Math.abs(decodedFrames - expectedFrames) <= 2048;
    assertTrue(withinACodecBlock, "decoded sample frames: %s".formatted(decodedFrames));
  }

  private long countRemainingSamples(final Buffer[] buffers) {
    long count = 0;
    for (final Buffer samples : buffers) {
      count += samples.remaining();
    }

    return count;
  }

  @Test
  void shouldSetBothBitratesWhenCompressingVideo() {
    final FfmpegCommand command = FfmpegTemplates.compressVideo(INPUT_NAME, OUTPUT_NAME, VIDEO_BITRATE, "96k");
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of(
      INPUT_FLAG,
      INPUT_NAME,
      VIDEO_CODEC_FLAG,
      "libx264",
      "-b:v",
      VIDEO_BITRATE,
      AUDIO_CODEC_FLAG,
      AAC_CODEC,
      "-b:a",
      "96k",
      OVERWRITE_FLAG,
      OUTPUT_NAME
    );
    assertEquals(expected, arguments);
  }

  @Test
  void shouldSeekBeforeTheInputWhenExtractingAClip() {
    final FfmpegCommand command = FfmpegTemplates.extractClip(INPUT_NAME, OUTPUT_NAME, "00:00:10", "5");
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of(
      SEEK_FLAG,
      "00:00:10",
      INPUT_FLAG,
      INPUT_NAME,
      "-t",
      "5",
      VIDEO_CODEC_FLAG,
      COPY_CODEC,
      AUDIO_CODEC_FLAG,
      COPY_CODEC,
      OVERWRITE_FLAG,
      OUTPUT_NAME
    );
    assertEquals(expected, arguments);
  }

  @Test
  void shouldWriteOneFrameWhenCreatingAThumbnail() {
    final FfmpegCommand command = FfmpegTemplates.createThumbnail(INPUT_NAME, THUMBNAIL_NAME, "3");
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of(SEEK_FLAG, "3", INPUT_FLAG, INPUT_NAME, "-frames:v", "1", OVERWRITE_FLAG, THUMBNAIL_NAME);
    assertEquals(expected, arguments);
  }

  @Test
  void shouldCopyBothStreamsWhenRemuxingVideo() {
    final FfmpegCommand command = FfmpegTemplates.remuxVideo(MATROSKA_INPUT_NAME, OUTPUT_NAME);
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of(
      INPUT_FLAG,
      MATROSKA_INPUT_NAME,
      VIDEO_CODEC_FLAG,
      COPY_CODEC,
      AUDIO_CODEC_FLAG,
      COPY_CODEC,
      OVERWRITE_FLAG,
      OUTPUT_NAME
    );
    assertEquals(expected, arguments);
  }

  @Test
  void shouldRejectMissingArguments() {
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.extractAudio(null, AAC_CODEC, PLACEHOLDER_OUTPUT));
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.extractAudio(PLACEHOLDER_INPUT, null, PLACEHOLDER_OUTPUT));
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.extractAudio(PLACEHOLDER_INPUT, AAC_CODEC, null));
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.extractOggVorbis(null, PLACEHOLDER_OUTPUT));
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.extractOggVorbis(PLACEHOLDER_INPUT, null));
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.compressVideo(null, PLACEHOLDER_OUTPUT, VIDEO_BITRATE, "1k"));
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.compressVideo(PLACEHOLDER_INPUT, null, VIDEO_BITRATE, "1k"));
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.compressVideo(PLACEHOLDER_INPUT, PLACEHOLDER_OUTPUT, null, "1k"));
    assertThrows(NullPointerException.class, () ->
      FfmpegTemplates.compressVideo(PLACEHOLDER_INPUT, PLACEHOLDER_OUTPUT, VIDEO_BITRATE, null)
    );
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.extractClip(null, PLACEHOLDER_OUTPUT, "0", "1"));
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.extractClip(PLACEHOLDER_INPUT, null, "0", "1"));
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.extractClip(PLACEHOLDER_INPUT, PLACEHOLDER_OUTPUT, null, "1"));
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.extractClip(PLACEHOLDER_INPUT, PLACEHOLDER_OUTPUT, "0", null));
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.createThumbnail(null, PLACEHOLDER_OUTPUT, "0"));
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.createThumbnail(PLACEHOLDER_INPUT, null, "0"));
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.createThumbnail(PLACEHOLDER_INPUT, PLACEHOLDER_OUTPUT, null));
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.remuxVideo(null, PLACEHOLDER_OUTPUT));
    assertThrows(NullPointerException.class, () -> FfmpegTemplates.remuxVideo(PLACEHOLDER_INPUT, null));
  }

  @Test
  void shouldNotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(FfmpegTemplates.class);
  }
}
