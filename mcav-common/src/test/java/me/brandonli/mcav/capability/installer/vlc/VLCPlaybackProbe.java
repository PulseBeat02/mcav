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
package me.brandonli.mcav.capability.installer.vlc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.file.FileSource;
import org.checkerframework.checker.nullness.qual.Nullable;
import uk.co.caprica.vlcj.binding.lib.LibVlc;

/**
 * The second half of {@link VLCReleaseTest}, run in a JVM of its own because libvlc can be loaded only once per JVM
 * and other tests load the system's VLC: loads the VLC installed in the folder it is given, never the system's, and
 * plays a video through it.
 *
 * <p>Arguments: the installation folder given to {@link VLCInstaller#create(Path)}, and a video with sound. It prints
 * {@link #PASSED} and exits with 0 once frames and sound arrived.
 */
final class VLCPlaybackProbe {

  static final String PASSED = "VLC PLAYBACK PASSED";

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private static final int FRAMES = 20;

  // half a second of 48 kHz stereo 16-bit sound
  private static final long AUDIO_BYTES = 96_000L;

  private VLCPlaybackProbe() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Loads the private installation and plays the video.
   *
   * @param arguments the installation folder and the video
   * @throws IOException          if the installation cannot be loaded
   * @throws InterruptedException if the wait for frames is interrupted
   */
  public static void main(final String[] arguments) throws IOException, InterruptedException {
    final Path folder = Path.of(arguments[0]);
    final Path video = Path.of(arguments[1]);
    final VLCInstaller installer = VLCInstaller.create(folder);
    final VLCInstallationKit kit = new VLCInstallationKit(installer, new PrivateDiscovery(), new VLCLoadState());
    final Optional<Path> loaded = kit.start();
    final String version = LibVlc.libvlc_get_version();
    System.out.println("libvlc " + version + " loaded from " + loaded.orElseThrow());
    final AtomicInteger frames = new AtomicInteger();
    final AtomicLong audioBytes = new AtomicLong();
    final VideoPlayerMultiplexer player = VideoPlayer.vlc();
    player
      .getVideoAttachableCallback()
      .attach(VideoPipelineStep.of((final ImageBuffer image, final OriginalVideoMetadata metadata) -> countFrame(frames)));
    player
      .getAudioAttachableCallback()
      .attach(AudioPipelineStep.of((final ByteBuffer samples, final OriginalAudioMetadata metadata) -> countSamples(audioBytes, samples)));
    final Source source = FileSource.path(video);
    final boolean started = player.start(source);
    final boolean played = started && Polling.pollUntil(TIMEOUT, () -> frames.get() >= FRAMES && audioBytes.get() >= AUDIO_BYTES);
    final boolean released = player.release();
    System.out.println("started " + started + ", frames " + frames.get() + ", audio bytes " + audioBytes.get() + ", released " + released);
    if (!played || !released) {
      System.exit(1);
    }
    System.out.println(PASSED);
    System.exit(0);
  }

  private static boolean countFrame(final AtomicInteger frames) {
    frames.incrementAndGet();
    return false;
  }

  private static boolean countSamples(final AtomicLong audioBytes, final ByteBuffer samples) {
    audioBytes.addAndGet(samples.remaining());
    return false;
  }

  /**
   * Finds no VLC on the system, so the kit loads the private installation.
   */
  private static final class PrivateDiscovery implements VLCDiscovery {

    private final NativeVLCDiscovery discovery = new NativeVLCDiscovery();

    @Override
    public boolean discoverSystemInstallation() {
      return false;
    }

    @Override
    public @Nullable Path getDiscoveredPath() {
      return this.discovery.getDiscoveredPath();
    }

    @Override
    public boolean loadInstalledLibraries(final Path libraryDirectory) {
      return this.discovery.loadInstalledLibraries(libraryDirectory);
    }
  }
}
