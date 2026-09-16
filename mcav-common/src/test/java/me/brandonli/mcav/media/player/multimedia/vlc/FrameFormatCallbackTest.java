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
package me.brandonli.mcav.media.player.multimedia.vlc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Dimension;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.VideoApi;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;

/**
 * Tests {@link FrameFormatCallback} and how {@link VideoRenderer} chooses the buffer VLC renders into.
 */
final class FrameFormatCallbackTest {

  private final MediaPlayer player = mock(MediaPlayer.class);
  private final VideoApi video = mock(VideoApi.class);
  private final VideoRenderer renderer;

  FrameFormatCallbackTest() {
    final VideoPlayerMultiplexer owner = mock(VideoPlayerMultiplexer.class);
    final DimensionAttachableCallback dimension = DimensionAttachableCallback.create();
    when(owner.getDimensionAttachableCallback()).thenReturn(dimension);
    final VideoRenderer real = new VideoRenderer(owner);
    this.renderer = spy(real);
  }

  private BufferFormat format(final Dimension trackSize, final int sourceWidth, final int sourceHeight) {
    when(this.player.video()).thenReturn(this.video);
    when(this.video.videoDimension()).thenReturn(trackSize);
    final FrameFormatCallback callback = new FrameFormatCallback(this.renderer, this.player);
    return callback.getBufferFormat(sourceWidth, sourceHeight);
  }

  @Test
  void rendersIntoAnRv32BufferOfThePaddedSize() {
    final Dimension trackSize = new Dimension(1920, 1080);
    final BufferFormat format = this.format(trackSize, 1920, 1088);
    final String chroma = format.getChroma();
    final int width = format.getWidth();
    final int height = format.getHeight();
    final int[] pitches = format.getPitches();
    final int[] lines = format.getLines();
    assertEquals("RV32", chroma);
    assertEquals(1920, width);
    assertEquals(1088, height);
    assertArrayEquals(new int[] { 1920 * 4 }, pitches);
    assertArrayEquals(new int[] { 1088 }, lines);
    verify(this.renderer).createBufferFormat(1920, 1088, 1920, 1080);
  }

  @ParameterizedTest
  @CsvSource(
    {
      "100, 60, 112, 64, 100, 60",
      "640, 480, 320, 240, 320, 240",
      "400, 200, 320, 240, 320, 200",
      "0, 240, 320, 240, 320, 240",
      "320, 0, 320, 240, 320, 240",
    }
  )
  void usesTheTrackSizeLimitedToTheBuffer(
    final int trackWidth,
    final int trackHeight,
    final int sourceWidth,
    final int sourceHeight,
    final int expectedWidth,
    final int expectedHeight
  ) {
    final Dimension trackSize = new Dimension(trackWidth, trackHeight);
    this.format(trackSize, sourceWidth, sourceHeight);
    verify(this.renderer).createBufferFormat(sourceWidth, sourceHeight, expectedWidth, expectedHeight);
  }

  @Test
  void usesTheBufferSizeWhenTheTrackReportsNoSize() {
    final BufferFormat format = this.format(null, 320, 240);
    final int width = format.getWidth();
    assertEquals(320, width);
    verify(this.renderer).createBufferFormat(320, 240, 320, 240);
  }
}
