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
package me.brandonli.mcav.browser;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.List;

final class DefaultBrowserOptionsBuilder implements BrowserOptionsBuilder {

  private static final int MINIMUM_FRAME_RATE = 1;

  private int frameRate;
  private boolean allowsJavaScriptJit;
  private boolean allowsPrivateNetworks;
  private boolean allowsAutoplay;
  private boolean confined;
  private List<String> refusedHosts;

  DefaultBrowserOptionsBuilder() {
    this.frameRate = BrowserOptions.MAXIMUM_FRAME_RATE;
    this.allowsJavaScriptJit = false;
    this.allowsPrivateNetworks = false;
    this.allowsAutoplay = false;
    this.confined = true;
    this.refusedHosts = List.of();
  }

  @Override
  public BrowserOptionsBuilder frameRate(final int frameRate) {
    this.frameRate = frameRate;
    return this;
  }

  @Override
  public BrowserOptionsBuilder javaScriptJit(final boolean allowsJavaScriptJit) {
    this.allowsJavaScriptJit = allowsJavaScriptJit;
    return this;
  }

  @Override
  public BrowserOptionsBuilder privateNetworks(final boolean allowsPrivateNetworks) {
    this.allowsPrivateNetworks = allowsPrivateNetworks;
    return this;
  }

  @Override
  public BrowserOptionsBuilder autoplay(final boolean allowsAutoplay) {
    this.allowsAutoplay = allowsAutoplay;
    return this;
  }

  @Override
  public BrowserOptionsBuilder confinement(final boolean confined) {
    this.confined = confined;
    return this;
  }

  @Override
  public BrowserOptionsBuilder refusedHosts(final Collection<String> refusedHosts) {
    Preconditions.checkNotNull(refusedHosts, "Refused hosts must not be null!");

    this.refusedHosts = List.copyOf(refusedHosts);
    return this;
  }

  @Override
  public BrowserOptions build() {
    Preconditions.checkArgument(
      this.frameRate >= MINIMUM_FRAME_RATE && this.frameRate <= BrowserOptions.MAXIMUM_FRAME_RATE,
      "Frame rate %s must be between 1 and %s frames per second!",
      this.frameRate,
      BrowserOptions.MAXIMUM_FRAME_RATE
    );
    for (final String host : this.refusedHosts) {
      Preconditions.checkArgument(HelperConfiguration.isHost(host), "Host %s is not a host name or address!", host);
    }

    return new BrowserOptions(
      this.frameRate,
      this.allowsJavaScriptJit,
      this.allowsPrivateNetworks,
      this.allowsAutoplay,
      this.confined,
      this.refusedHosts
    );
  }
}
