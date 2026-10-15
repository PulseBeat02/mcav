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

import com.google.common.base.MoreObjects;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Immutable options for how a {@link BrowserPlayer} treats pages; instances may be shared across threads.
 * The defaults suit untrusted web content: the browser runs
 * without the Chromium sandbox, which the embedded browser cannot use, so JavaScript runs without V8's just-in-time
 * compiler, the part of Chromium most exploits target. Pages load slower that way; turn it back on only for pages you
 * trust.
 *
 * <p>Pages reach public addresses of the internet only, by default: every connection of the browser goes through a
 * guard in the helper process that resolves the host itself and refuses loopback, private, link-local (such as the
 * metadata service of a cloud machine) and other special addresses, so neither a page nor a player clicking on it can
 * use the server to look into its own network. Allow private networks only to show pages of your own network. Inside a
 * container the guard cannot see the public address of the machine the container runs on, which reaches the
 * services that listen on every interface of that machine: name it in {@link BrowserOptionsBuilder#refusedHosts(Collection)}.
 *
 * <p>On Linux and macOS, Chromium is confined, by default: its processes cannot read the server's folder, the home
 * folder of the server's user or the server's temporary folder, apart from what the browser needs there, and they
 * change files only in the folder of their session. A page that exploits a flaw of Chromium then cannot read the
 * server's configuration or change its files. The confinement needs Landlock, which Linux has since 5.13, or Seatbelt
 * on macOS; on Windows, and on an older Linux kernel, the browser runs as before and the server log says so.
 *
 * <pre>{@code
 *   final BrowserOptions options = BrowserOptions.builder().frameRate(30).build();
 *   final BrowserPlayer browser = BrowserPlayer.create(options);
 * }</pre>
 *
 * @since 1.0.0
 */
public final class BrowserOptions {

  /**
   * The highest frame rate a browser may paint at. This is mcav's limit, not CEF's: CEF's frame rate setting has a
   * minimum of 1 and a default of 30, and no maximum.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_FRAME_RATE = HelperConfiguration.MAXIMUM_FRAME_RATE;

  /**
   * The options {@link BrowserPlayer#create()} uses: 60 frames per second at most, no JavaScript JIT, public addresses
   * only, sound only once someone clicked or typed into the page, and Chromium confined where the system can.
   *
   * @since 1.0.0
   */
  public static final BrowserOptions DEFAULT = builder().build();

  private final int frameRate;
  private final boolean allowsJavaScriptJit;
  private final boolean allowsPrivateNetworks;
  private final boolean allowsAutoplay;
  private final boolean confined;
  private final List<String> refusedHosts;

  BrowserOptions(
    final int frameRate,
    final boolean allowsJavaScriptJit,
    final boolean allowsPrivateNetworks,
    final boolean allowsAutoplay,
    final boolean confined,
    final List<String> refusedHosts
  ) {
    this.frameRate = frameRate;
    this.allowsJavaScriptJit = allowsJavaScriptJit;
    this.allowsPrivateNetworks = allowsPrivateNetworks;
    this.allowsAutoplay = allowsAutoplay;
    this.confined = confined;
    this.refusedHosts = refusedHosts;
  }

  /**
   * Creates a builder with the default options.
   *
   * @return the builder
   * @since 1.0.0
   */
  public static BrowserOptionsBuilder builder() {
    return new DefaultBrowserOptionsBuilder();
  }

  /**
   * Returns how many frames per second the browser paints at most. A page that does not change paints no frames.
   *
   * @return the frame rate, from 1 to {@value #MAXIMUM_FRAME_RATE}
   * @since 1.0.0
   */
  public int getFrameRate() {
    return this.frameRate;
  }

  /**
   * Returns whether JavaScript is compiled to machine code.
   *
   * @return true if the configuration enables V8's just-in-time compiler when a browser is started
   * @since 1.0.0
   */
  public boolean isJavaScriptJit() {
    return this.allowsJavaScriptJit;
  }

  /**
   * Returns whether pages may reach loopback, private and other non-public addresses.
   *
   * @return true if the address policy permits private and other non-public destinations; connectivity
   *         is still subject to the host network and other browser policies
   * @since 1.0.0
   */
  public boolean isPrivateNetworks() {
    return this.allowsPrivateNetworks;
  }

  /**
   * Returns whether a page may play sound before anyone clicked or typed into it.
   *
   * @return true if the autoplay policy permits sound without a user gesture; this does not guarantee
   *         that a page produces capturable audio
   * @since 1.0.0
   */
  public boolean isAutoplay() {
    return this.allowsAutoplay;
  }

  /**
   * Returns whether Chromium is confined where the system can confine it, see {@link BrowserOptionsBuilder#confinement(boolean)}.
   *
   * @return true if Chromium is confined
   * @since 1.0.0
   */
  public boolean isConfined() {
    return this.confined;
  }

  /**
   * Returns the names and addresses whose addresses pages may not reach, besides the private ones and the machine's own,
   * see {@link BrowserOptionsBuilder#refusedHosts(Collection)}.
   *
   * @return the hosts, unmodifiable
   * @since 1.0.0
   */
  public List<String> getRefusedHosts() {
    return this.refusedHosts;
  }

  /**
   * Returns whether {@code other} is options with the same values.
   *
   * @param other the object to compare with
   * @return whether every option matches
   * @since 1.0.0
   */
  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final BrowserOptions options)) {
      return false;
    }

    return (
      this.frameRate == options.frameRate &&
      this.allowsJavaScriptJit == options.allowsJavaScriptJit &&
      this.allowsPrivateNetworks == options.allowsPrivateNetworks &&
      this.allowsAutoplay == options.allowsAutoplay &&
      this.confined == options.confined &&
      this.refusedHosts.equals(options.refusedHosts)
    );
  }

  /**
   * Returns a hash code built from every option.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    return Objects.hash(
      this.frameRate,
      this.allowsJavaScriptJit,
      this.allowsPrivateNetworks,
      this.allowsAutoplay,
      this.confined,
      this.refusedHosts
    );
  }

  /**
   * Returns a debugging representation with every option.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("frameRate", this.frameRate)
      .add("javaScriptJit", this.allowsJavaScriptJit)
      .add("privateNetworks", this.allowsPrivateNetworks)
      .add("autoplay", this.allowsAutoplay)
      .add("confined", this.confined)
      .add("refusedHosts", this.refusedHosts)
      .toString();
  }
}
