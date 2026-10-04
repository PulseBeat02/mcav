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
package me.brandonli.mcav.media.player.multimedia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.CapabilityGuard;
import me.brandonli.mcav.media.player.multimedia.vlc.VLCPlayer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;

/**
 * Tests the VLC factory of {@link VideoPlayer}. The tests that mark VLC in the shared guard forget it again, so the
 * other tests of the JVM can still create VLC players.
 */
final class VideoPlayerFactoriesTest {

  @Test
  void refusesVlcPlayersWhileVlcIsBeingPrepared() {
    final CapabilityGuard guard = CapabilityGuard.shared();
    guard.markPreparing(Capability.VLC);
    try {
      final IllegalStateException exception = assertThrows(IllegalStateException.class, VideoPlayer::vlc);
      final String message = exception.getMessage();
      assertEquals(
        "VLC is still being prepared in the background; wait for MCAVApi.whenCapabilityReady(Capability.VLC) or try again shortly",
        message
      );
    } finally {
      guard.forget(Capability.VLC);
    }
  }

  @Test
  void refusesVlcPlayersWhenVlcIsNotAvailable() {
    final CapabilityGuard guard = CapabilityGuard.shared();
    guard.markPrepared(Capability.VLC, false);
    try {
      final IllegalStateException exception = assertThrows(IllegalStateException.class, () -> new VLCPlayer("--x"));
      final String message = exception.getMessage();
      assertEquals("VLC is not available on this system", message);
    } finally {
      guard.forget(Capability.VLC);
    }
  }

  @Test
  void createsVlcPlayersWithoutLoadingVlc() {
    try (final MockedConstruction<MediaPlayerFactory> factories = Mockito.mockConstruction(MediaPlayerFactory.class)) {
      final VideoPlayer withoutOptions = VideoPlayer.vlc();
      final VideoPlayer withOptions = VideoPlayer.vlc("--no-video-title-show");
      final VLCPlayer first = assertInstanceOf(VLCPlayer.class, withoutOptions);
      final VLCPlayer second = assertInstanceOf(VLCPlayer.class, withOptions);
      assertEquals(List.of(), factories.constructed(), "creating a player must not initialize the VLC engine");
      first.release();
      second.release();
    }
  }

  @Test
  void rejectsNullVlcOptions() {
    assertThrows(NullPointerException.class, () -> VideoPlayer.vlc((String[]) null));
  }
}
