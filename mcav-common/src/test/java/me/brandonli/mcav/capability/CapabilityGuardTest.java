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
package me.brandonli.mcav.capability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link CapabilityGuard}. Only separate guards are changed, so the shared guard of the library stays untouched.
 */
final class CapabilityGuardTest {

  private static final String VLC_PREPARING =
    "VLC is still being prepared in the background; wait for MCAVApi.whenCapabilityReady(Capability.VLC) or try again shortly";
  private static final String YTDLP_PREPARING =
    "yt-dlp is still being prepared in the background; wait for MCAVApi.whenCapabilityReady(Capability.YT_DLP) or try again shortly";

  private final CapabilityGuard guard = new CapabilityGuard();

  @Test
  void sharesOneGuardPerJvm() {
    final CapabilityGuard first = CapabilityGuard.shared();
    final CapabilityGuard second = CapabilityGuard.shared();
    assertSame(first, second);
    assertNotSame(first, this.guard);
  }

  @Test
  void refusesNothingItKnowsNothingAbout() {
    for (final Capability capability : Capability.values()) {
      final boolean preparing = this.guard.isPreparing(capability);
      assertFalse(preparing, capability::name);
      assertDoesNotThrow(() -> this.guard.checkNotPreparing(capability));
      assertDoesNotThrow(() -> this.guard.checkUsable(capability));
    }
  }

  @Test
  void refusesACapabilityWhileItIsBeingPrepared() {
    this.guard.markPreparing(Capability.VLC);
    final boolean vlcPreparing = this.guard.isPreparing(Capability.VLC);
    final boolean ytdlpPreparing = this.guard.isPreparing(Capability.YT_DLP);
    final IllegalStateException notPreparing = assertThrows(IllegalStateException.class, () -> this.guard.checkNotPreparing(Capability.VLC)
    );
    final IllegalStateException usable = assertThrows(IllegalStateException.class, () -> this.guard.checkUsable(Capability.VLC));
    final String notPreparingMessage = notPreparing.getMessage();
    final String usableMessage = usable.getMessage();
    assertTrue(vlcPreparing);
    assertFalse(ytdlpPreparing, "only the marked capability is refused");
    assertEquals(VLC_PREPARING, notPreparingMessage);
    assertEquals(VLC_PREPARING, usableMessage);
  }

  @Test
  void namesTheCapabilityInTheMessage() {
    this.guard.markPreparing(Capability.YT_DLP);
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () -> this.guard.checkNotPreparing(Capability.YT_DLP)
    );
    final String message = exception.getMessage();
    assertEquals(YTDLP_PREPARING, message);
  }

  @Test
  void stopsGuardingACapabilityThatBecameAvailable() {
    this.guard.markPreparing(Capability.VLC);
    this.guard.markPrepared(Capability.VLC, true);
    final boolean preparing = this.guard.isPreparing(Capability.VLC);
    assertFalse(preparing);
    assertDoesNotThrow(() -> this.guard.checkUsable(Capability.VLC));
  }

  @Test
  void refusesAnUnavailableCapabilityOnlyWhereItMustBeUsable() {
    this.guard.markPreparing(Capability.VLC);
    this.guard.markPrepared(Capability.VLC, false);
    final boolean preparing = this.guard.isPreparing(Capability.VLC);
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () -> this.guard.checkUsable(Capability.VLC));
    final String message = exception.getMessage();
    assertFalse(preparing);
    assertEquals("VLC is not available on this system", message, "a missing program is told apart from one being prepared");
    assertDoesNotThrow(() -> this.guard.checkNotPreparing(Capability.VLC), "a failed program may still be tried by features");
  }

  @Test
  void forgetsWhatItRecorded() {
    this.guard.markPrepared(Capability.VLC, false);
    this.guard.markPreparing(Capability.YT_DLP);
    this.guard.forget(Capability.VLC);
    this.guard.forget(Capability.YT_DLP);
    assertDoesNotThrow(() -> this.guard.checkUsable(Capability.VLC));
    assertDoesNotThrow(() -> this.guard.checkUsable(Capability.YT_DLP));
  }

  @Test
  void rejectsNulls() {
    assertThrows(NullPointerException.class, () -> this.guard.markPreparing(null));
    assertThrows(NullPointerException.class, () -> this.guard.markPrepared(null, true));
    assertThrows(NullPointerException.class, () -> this.guard.forget(null));
    assertThrows(NullPointerException.class, () -> this.guard.isPreparing(null));
    assertThrows(NullPointerException.class, () -> this.guard.checkNotPreparing(null));
    assertThrows(NullPointerException.class, () -> this.guard.checkUsable(null));
  }
}
