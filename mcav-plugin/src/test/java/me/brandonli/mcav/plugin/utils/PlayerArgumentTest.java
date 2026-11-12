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
package me.brandonli.mcav.plugin.utils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class PlayerArgumentTest {

  @ParameterizedTest
  @EnumSource(PlayerArgument.class)
  void shouldCreateANewPlayerOfItsBackendForEveryVideo(final PlayerArgument argument) {
    final VideoPlayerMultiplexer first = mock(VideoPlayerMultiplexer.class);
    final VideoPlayerMultiplexer second = mock(VideoPlayerMultiplexer.class);
    final MockedStatic.Verification factory = this.factoryOf(argument);
    try (final MockedStatic<VideoPlayer> players = Mockito.mockStatic(VideoPlayer.class)) {
      players.when(factory).thenReturn(first, second);

      final VideoPlayerMultiplexer created = argument.createPlayer();
      final VideoPlayerMultiplexer createdNext = argument.createPlayer();
      assertSame(first, created);
      assertSame(second, createdNext);
    }
  }

  private MockedStatic.Verification factoryOf(final PlayerArgument argument) {
    return switch (argument) {
      case VLC -> VideoPlayer::vlc;
      case FFMPEG -> VideoPlayer::ffmpeg;
      case DEVICE -> VideoPlayer::device;
    };
  }
}
