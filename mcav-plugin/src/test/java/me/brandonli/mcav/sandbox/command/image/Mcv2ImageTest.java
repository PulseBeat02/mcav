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
package me.brandonli.mcav.sandbox.command.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.sandbox.command.video.Mcv2Output;
import me.brandonli.mcav.sandbox.testing.TestServer;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests {@link Mcv2Image}: the screen starts with the image and is given a copy of it twice a second, off the main
 * thread, until it is released.
 */
final class Mcv2ImageTest {

  private static final int[] PIXELS = { 0xFF102030, 0xFF405060, 0xFF708090, 0xFFA0B0C0, 0xFFD0E0F0, 0xFF000000 };

  private Plugin plugin;

  private Mcv2Output output;

  private BukkitTask task;

  private final List<int[]> given = new ArrayList<>();

  @BeforeEach
  void createImage() {
    TestServer.reset();
    this.plugin = mock(Plugin.class);
    this.output = mock(Mcv2Output.class);
    this.task = mock(BukkitTask.class);
    when(TestServer.scheduler().runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), anyLong(), anyLong())).thenReturn(
      this.task
    );
    doAnswer(invocation -> {
      final ImageBuffer frame = invocation.getArgument(0);
      final OriginalVideoMetadata metadata = invocation.getArgument(1);
      assertEquals(3, metadata.getVideoWidth());
      assertEquals(2, metadata.getVideoHeight());
      this.given.add(frame.getPixels().clone());
      // the screen may resize the frame in place: it is the screen's own copy
      frame.getPixels()[0] = 0;
      return true;
    })
      .when(this.output)
      .applyFilter(any(), any());
  }

  @Test
  void startsTheScreenAndGivesItTheImageTwiceASecondUntilReleased() {
    final Mcv2Image image = new Mcv2Image(this.plugin, this.output);
    final ImageBuffer picture = ImageBuffer.buffer(PIXELS.clone(), 3, 2);

    image.displayImage(picture);
    // the caller keeps its image and may change or release it
    picture.getPixels()[1] = 0;

    verify(this.output).start();
    final ArgumentCaptor<Runnable> feeds = ArgumentCaptor.forClass(Runnable.class);
    verify(TestServer.scheduler()).runTaskTimerAsynchronously(eq(this.plugin), feeds.capture(), eq(0L), eq(Mcv2Image.PERIOD_TICKS));
    feeds.getValue().run();
    feeds.getValue().run();
    assertEquals(2, this.given.size());
    assertArrayEquals(PIXELS, this.given.getFirst());
    assertArrayEquals(PIXELS, this.given.getLast(), "every frame is a fresh copy of the image");
    image.release();
    verify(this.task).cancel();
    verify(this.output).release();
    image.release();
    verify(this.task, times(1)).cancel();
  }

  @Test
  void releasesAScreenThatShowedNothing() {
    final Mcv2Image image = new Mcv2Image(this.plugin, this.output);
    image.release();
    verify(this.output).release();
    verify(this.output, never()).start();
  }

  @Test
  void refusesMissingParts() {
    assertThrows(NullPointerException.class, () -> new Mcv2Image(null, this.output));
    assertThrows(NullPointerException.class, () -> new Mcv2Image(this.plugin, null));
    assertThrows(NullPointerException.class, () -> new Mcv2Image(this.plugin, this.output).displayImage(null));
  }
}
