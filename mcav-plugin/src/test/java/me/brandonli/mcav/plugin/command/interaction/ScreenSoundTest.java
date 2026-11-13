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
package me.brandonli.mcav.plugin.command.interaction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.google.common.base.MoreObjects;
import java.util.Arrays;
import java.util.Objects;
import me.brandonli.mcav.plugin.testing.EqualityAssertions;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ScreenSoundTest {

  private Player[] viewers;

  @BeforeEach
  void setUp() {
    final Player viewer = mock(Player.class);
    this.viewers = new Player[] { viewer };
  }

  @Test
  void shouldFollowTheEqualityContract() {
    final ScreenSound sound = new ScreenSound(AudioArgument.HTTP_SERVER, this.viewers);
    final ScreenSound equalSound = new ScreenSound(AudioArgument.HTTP_SERVER, this.viewers);
    final ScreenSound otherType = new ScreenSound(AudioArgument.NONE, this.viewers);
    final Player otherViewer = mock(Player.class);
    final Player[] otherViewers = new Player[] { otherViewer };
    final ScreenSound otherViewersSound = new ScreenSound(AudioArgument.HTTP_SERVER, otherViewers);
    EqualityAssertions.assertEqualityContract(sound, equalSound, otherType, otherViewersSound);
  }

  @Test
  void shouldHashTheTypeThenTheViewers() {
    final ScreenSound sound = new ScreenSound(AudioArgument.HTTP_SERVER, this.viewers);

    final int hash = sound.hashCode();
    final int viewersHash = Arrays.hashCode(this.viewers);
    final int expected = Objects.hash(AudioArgument.HTTP_SERVER, viewersHash);
    assertEquals(expected, hash);
  }

  @Test
  void shouldRenderTheTypeAndTheViewers() {
    final ScreenSound sound = new ScreenSound(AudioArgument.HTTP_SERVER, this.viewers);

    final String text = sound.toString();
    final String expected = MoreObjects.toStringHelper("ScreenSound")
      .add("type", AudioArgument.HTTP_SERVER)
      .add("viewers", this.viewers)
      .toString();
    assertEquals(expected, text);
  }

  @Test
  void shouldKeepItsOwnCopyOfTheViewers() {
    final ScreenSound sound = new ScreenSound(AudioArgument.HTTP_SERVER, this.viewers);
    final Player[] expected = this.viewers.clone();
    final Player otherViewer = mock(Player.class);
    this.viewers[0] = otherViewer;
    final Player[] returned = sound.getViewers();
    returned[0] = otherViewer;

    final Player[] kept = sound.getViewers();
    assertArrayEquals(expected, kept);
  }
}
