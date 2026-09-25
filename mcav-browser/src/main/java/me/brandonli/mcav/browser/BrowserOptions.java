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

/**
 * How a {@link BrowserPlayer} treats the pages it shows. The defaults suit untrusted web content: the browser runs
 * without the Chromium sandbox, which the embedded browser cannot use, so JavaScript runs without V8's just-in-time
 * compiler, the part of Chromium most exploits target. Pages load slower that way; turn it back on only for pages you
 * trust.
 *
 * <p>Pages reach public addresses of the internet only, by default: every connection of the browser goes through a
 * guard in the helper process that resolves the host itself and refuses loopback, private, link-local (such as the
 * metadata service of a cloud machine) and other special addresses, so neither a page nor a player clicking on it can
 * use the server to look into its own network. Allow private networks only to show pages of your own network.
 *
 * <pre>{@code
 *   final BrowserOptions options = BrowserOptions.builder().frameRate(30).build();
 *   final BrowserPlayer browser = BrowserPlayer.create(options);
 * }</pre>
 */
public final class BrowserOptions {

  /**
   * The highest frame rate: CEF paints an off-screen browser at most 60 times per second.
   */
  public static final int MAX_FRAME_RATE = HelperConfiguration.MAX_FRAME_RATE;

  /**
   * The options {@link BrowserPlayer#create()} uses: 60 frames per second at most, no JavaScript JIT, public addresses
   * only.
   */
  public static final BrowserOptions DEFAULT = builder().build();

  private final int frameRate;
  private final boolean javaScriptJit;
  private final boolean privateNetworks;

  private BrowserOptions(final int frameRate, final boolean javaScriptJit, final boolean privateNetworks) {
    this.frameRate = frameRate;
    this.javaScriptJit = javaScriptJit;
    this.privateNetworks = privateNetworks;
  }

  /**
   * Creates a builder with the default options.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Gets how many frames per second the browser paints at most. A page that does not change paints no frames.
   *
   * @return the frame rate, from 1 to {@value #MAX_FRAME_RATE}
   */
  public int getFrameRate() {
    return this.frameRate;
  }

  /**
   * Checks whether JavaScript is compiled to machine code.
   *
   * @return true if V8's just-in-time compiler runs
   */
  public boolean isJavaScriptJit() {
    return this.javaScriptJit;
  }

  /**
   * Checks whether pages may reach loopback, private and other non-public addresses.
   *
   * @return true if the browser connects to any address
   */
  public boolean isPrivateNetworks() {
    return this.privateNetworks;
  }

  /**
   * Builds {@link BrowserOptions}.
   */
  public static final class Builder {

    private int frameRate = MAX_FRAME_RATE;
    private boolean javaScriptJit;
    private boolean privateNetworks;

    private Builder() {}

    /**
     * Sets how many frames per second the browser paints at most.
     *
     * @param frameRate the frame rate, from 1 to {@value BrowserOptions#MAX_FRAME_RATE}
     * @return this builder
     * @throws IllegalArgumentException if the frame rate is out of range
     */
    public Builder frameRate(final int frameRate) {
      Preconditions.checkArgument(
        frameRate >= 1 && frameRate <= MAX_FRAME_RATE,
        "Frame rate must be between 1 and %s but was %s",
        MAX_FRAME_RATE,
        frameRate
      );
      this.frameRate = frameRate;
      return this;
    }

    /**
     * Turns V8's just-in-time compiler on or off. It is off by default, because the pages run without the Chromium
     * sandbox; turn it on only for pages you trust.
     *
     * @param javaScriptJit true to compile JavaScript to machine code
     * @return this builder
     */
    public Builder javaScriptJit(final boolean javaScriptJit) {
      this.javaScriptJit = javaScriptJit;
      return this;
    }

    /**
     * Lets pages reach loopback, private, link-local and other non-public addresses, such as a dashboard in the
     * server's own network. Off by default: a page, or a player clicking on it, could otherwise read services the
     * server can reach but the players cannot.
     *
     * @param privateNetworks true to let the browser connect to any address
     * @return this builder
     */
    public Builder privateNetworks(final boolean privateNetworks) {
      this.privateNetworks = privateNetworks;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     */
    public BrowserOptions build() {
      return new BrowserOptions(this.frameRate, this.javaScriptJit, this.privateNetworks);
    }
  }
}
