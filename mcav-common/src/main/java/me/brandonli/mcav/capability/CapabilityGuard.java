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

import com.google.common.base.Preconditions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which capabilities are still being prepared in the background, and which could not be prepared, so the
 * features behind them can refuse clearly instead of failing somewhere deep inside.
 *
 * <p>{@link me.brandonli.mcav.MCAVApi#install(Class[])} marks {@link Capability#VLC} and {@link Capability#YT_DLP} as
 * preparing before it returns, and records the outcome once the background installation finished. The programs
 * behind these capabilities exist once per JVM, so {@link #shared()} is the guard the library consults: VLC players
 * refuse to be created while VLC is preparing or unavailable, and the default yt-dlp parser refuses to run while
 * yt-dlp is preparing. A capability the guard knows nothing about, because no installation prepared it, is never
 * refused.
 *
 * <p>All methods are thread-safe.
 */
public final class CapabilityGuard {

  private static final CapabilityGuard SHARED = new CapabilityGuard();

  private final Map<Capability, State> states;

  /**
   * Constructs a guard that knows nothing about any capability and is independent of {@link #shared()}. The library
   * itself always uses the shared guard; separate guards are for tests and custom bootstraps.
   */
  public CapabilityGuard() {
    this.states = new ConcurrentHashMap<>();
  }

  /**
   * Gets the guard of the JVM, which the library records its background installations in and which VLC players and
   * the default yt-dlp parser consult.
   *
   * @return the shared guard
   */
  public static CapabilityGuard shared() {
    return SHARED;
  }

  /**
   * Records that a capability is being prepared in the background.
   *
   * @param capability the capability
   */
  public void markPreparing(final Capability capability) {
    Preconditions.checkNotNull(capability, "Capability must not be null");
    this.states.put(capability, State.PREPARING);
  }

  /**
   * Records the outcome of preparing a capability. An available capability is no longer guarded at all; an
   * unavailable one is refused by {@link #checkUsable(Capability)} until it is {@linkplain #forget(Capability)
   * forgotten}.
   *
   * @param capability the capability
   * @param available  true if the capability can be used now
   */
  public void markPrepared(final Capability capability, final boolean available) {
    Preconditions.checkNotNull(capability, "Capability must not be null");
    if (available) {
      this.states.remove(capability);
      return;
    }
    this.states.put(capability, State.UNAVAILABLE);
  }

  /**
   * Forgets everything recorded about a capability, so it is no longer refused. Called when the library is released.
   *
   * @param capability the capability
   */
  public void forget(final Capability capability) {
    Preconditions.checkNotNull(capability, "Capability must not be null");
    this.states.remove(capability);
  }

  /**
   * Checks whether a capability is still being prepared in the background.
   *
   * @param capability the capability
   * @return true if its preparation has not finished yet
   */
  public boolean isPreparing(final Capability capability) {
    Preconditions.checkNotNull(capability, "Capability must not be null");
    final State state = this.states.get(capability);
    return state == State.PREPARING;
  }

  /**
   * Fails if a capability is still being prepared in the background. A capability whose preparation failed is not
   * refused, so a feature that can prepare the program on its own, such as the yt-dlp parser, can still try.
   *
   * @param capability the capability
   * @throws IllegalStateException if the capability is still being prepared
   */
  public void checkNotPreparing(final Capability capability) {
    Preconditions.checkNotNull(capability, "Capability must not be null");
    final boolean preparing = this.isPreparing(capability);
    if (preparing) {
      final String message = describePreparing(capability);
      throw new IllegalStateException(message);
    }
  }

  /**
   * Fails if a capability is still being prepared in the background, or if its preparation found that it is not
   * available on this system. The two cases fail with different messages.
   *
   * @param capability the capability
   * @throws IllegalStateException if the capability is still being prepared or is not available on this system
   */
  public void checkUsable(final Capability capability) {
    Preconditions.checkNotNull(capability, "Capability must not be null");
    this.checkNotPreparing(capability);
    final State state = this.states.get(capability);
    if (state == State.UNAVAILABLE) {
      final String name = capability.getDisplayName();
      throw new IllegalStateException(name + " is not available on this system");
    }
  }

  private static String describePreparing(final Capability capability) {
    final String name = capability.getDisplayName();
    final String constant = capability.name();
    return "%s is still being prepared in the background; wait for MCAVApi.whenCapabilityReady(Capability.%s) or try again shortly".formatted(
        name,
        constant
      );
  }

  /**
   * What the guard knows about a capability. A capability without a state is not guarded.
   */
  private enum State {
    PREPARING,
    UNAVAILABLE,
  }
}
