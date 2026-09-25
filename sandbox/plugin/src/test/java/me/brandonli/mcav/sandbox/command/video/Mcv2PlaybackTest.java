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
package me.brandonli.mcav.sandbox.command.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Channel;
import me.brandonli.mcav.media.mcv2.Mcv2Format;
import me.brandonli.mcav.media.mcv2.encode.FrameWriter;
import me.brandonli.mcav.media.mcv2.encode.TreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class Mcv2PlaybackTest {

  private Mcv2Channel channel;

  static List<byte[]> stream() {
    final FrameWriter.Options options = FrameWriter.Options.production(false);
    final TreeNode solid = TreeNode.leaf(Mcv2Format.MODE_SOLID, 0, new byte[] { 1, 2, 3 });
    final TreeNode motion = TreeNode.leaf(Mcv2Format.MODE_MOTION, 0, new byte[] { 1, 1 });
    return List.of(
      FrameWriter.write(32, 32, 0, 0, true, 0, 0, List.of(solid), options),
      FrameWriter.write(32, 32, 1, 0, false, 0, 0, List.of(motion), options),
      FrameWriter.write(32, 32, 2, 1, false, 0, 0, List.of(motion), options)
    );
  }

  @BeforeEach
  void createChannel() {
    this.channel = mock(Mcv2Channel.class);
  }

  private long[] sent(final int times) {
    final ArgumentCaptor<byte[]> frames = ArgumentCaptor.forClass(byte[].class);
    verify(this.channel, org.mockito.Mockito.times(times)).send(frames.capture());
    final List<byte[]> all = frames.getAllValues();
    final byte[] last = all.getLast();
    return new long[] { Mcv2Format.u32(last, 12), Mcv2Format.u32(last, 16) };
  }

  @Test
  void waitsForAViewer() {
    when(this.channel.getRecipients()).thenReturn(Set.of());
    new Mcv2Playback(this.channel, stream()).run();
    verify(this.channel).update();
    verify(this.channel, never()).send(any());
  }

  @Test
  void loopsWithIdsThatKeepIncreasing() {
    when(this.channel.getRecipients()).thenReturn(Set.of(UUID.randomUUID()));
    final Mcv2Playback playback = new Mcv2Playback(this.channel, stream());
    playback.run();
    assertEquals(0, this.sent(1)[0]);
    playback.run();
    playback.run();
    final long[] third = this.sent(3);
    assertEquals(2, third[0]);
    assertEquals(1, third[1]);
    // the fourth run starts the stream over, three ids later
    playback.run();
    final long[] fourth = this.sent(4);
    assertEquals(3, fourth[0]);
    assertEquals(3, fourth[1]);
  }

  @Test
  void startsOverForANewViewer() {
    when(this.channel.getRecipients()).thenReturn(Set.of(UUID.randomUUID()));
    when(this.channel.takeKeyframeRequest()).thenReturn(true, false, true);
    final Mcv2Playback playback = new Mcv2Playback(this.channel, stream());
    // a request before the first frame changes nothing: the first frame is the keyframe
    playback.run();
    assertEquals(0, this.sent(1)[0]);
    playback.run();
    // a request in the middle starts over from the keyframe, three ids later
    playback.run();
    final long[] restarted = this.sent(3);
    assertEquals(3, restarted[0]);
    assertEquals(3, restarted[1]);
  }

  @Test
  void refusesAnEmptyStream() {
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Playback(this.channel, List.of()));
    assertThrows(NullPointerException.class, () -> new Mcv2Playback(null, stream()));
  }
}
