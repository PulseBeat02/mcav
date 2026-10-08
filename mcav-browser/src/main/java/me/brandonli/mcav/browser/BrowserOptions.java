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
 * services that listen on every interface of that machine: name it in {@link Builder#refusedHosts(Collection)}.
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
 */
public final class BrowserOptions {

  /**
   * The highest frame rate a browser may paint at. This is mcav's limit, not CEF's: CEF's frame rate setting has a
   * minimum of 1 and a default of 30, and no maximum.
   */
  public static final int MAX_FRAME_RATE = HelperConfiguration.MAX_FRAME_RATE;

  /**
   * The options {@link BrowserPlayer#create()} uses: 60 frames per second at most, no JavaScript JIT, public addresses
   * only, sound only once someone clicked or typed into the page, and Chromium confined where the system can.
   */
  public static final BrowserOptions DEFAULT = builder().build();

  private final int frameRate;
  private final boolean allowsJavaScriptJit;
  private final boolean allowsPrivateNetworks;
  private final boolean allowsAutoplay;
  private final boolean confined;
  private final List<String> refusedHosts;

  private BrowserOptions(
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
   * @return true if the configuration enables V8's just-in-time compiler when a browser is started
   */
  public boolean isJavaScriptJit() {
    return this.allowsJavaScriptJit;
  }

  /**
   * Checks whether pages may reach loopback, private and other non-public addresses.
   *
   * @return true if the address policy permits private and other non-public destinations; connectivity
   *         is still subject to the host network and other browser policies
   */
  public boolean isPrivateNetworks() {
    return this.allowsPrivateNetworks;
  }

  /**
   * Checks whether a page may play sound before anyone clicked or typed into it.
   *
   * @return true if the autoplay policy permits sound without a user gesture; this does not guarantee
   *         that a page produces capturable audio
   */
  public boolean isAutoplay() {
    return this.allowsAutoplay;
  }

  /**
   * Checks whether Chromium is confined where the system can confine it, see {@link Builder#confinement(boolean)}.
   *
   * @return true if Chromium is confined
   */
  public boolean isConfined() {
    return this.confined;
  }

  /**
   * Gets the names and addresses whose addresses pages may not reach, besides the private ones and the machine's own,
   * see {@link Builder#refusedHosts(Collection)}.
   *
   * @return the hosts, unmodifiable
   */
  public List<String> getRefusedHosts() {
    return this.refusedHosts;
  }

  /**
   * Builds immutable {@link BrowserOptions}. Builders are mutable and not thread-safe;
   * {@link #build()} snapshots their current values.
   */
  public static final class Builder {

    private int frameRate = MAX_FRAME_RATE;
    private boolean allowsJavaScriptJit;
    private boolean allowsPrivateNetworks;
    private boolean allowsAutoplay;
    private boolean confined = true;
    private List<String> refusedHosts = List.of();

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
     * @param allowsJavaScriptJit true to compile JavaScript to machine code
     * @return this builder
     */
    public Builder javaScriptJit(final boolean allowsJavaScriptJit) {
      this.allowsJavaScriptJit = allowsJavaScriptJit;
      return this;
    }

    /**
     * Lets pages reach loopback, private, link-local and other non-public addresses, such as a dashboard in the
     * server's own network. Off by default: a page, or a player clicking on it, could otherwise read services the
     * server can reach but the players cannot.
     *
     * @param allowsPrivateNetworks true to let the browser connect to any address
     * @return this builder
     */
    public Builder privateNetworks(final boolean allowsPrivateNetworks) {
      this.allowsPrivateNetworks = allowsPrivateNetworks;
      return this;
    }

    /**
     * Lets pages play sound right away. Off by default: as in a desktop browser, a page plays sound only once someone
     * clicked or typed into it, such as a player who clicks the screen of the browser.
     *
     * @param allowsAutoplay true to let pages play sound before anyone clicked or typed into them
     * @return this builder
     */
    public Builder autoplay(final boolean allowsAutoplay) {
      this.allowsAutoplay = allowsAutoplay;
      return this;
    }

    /**
     * Confines Chromium on Linux and macOS, or lets it run as before. On by default: Chromium's processes then cannot
     * read the server's folder, the home folder of the server's user or the server's temporary folder, apart from Java,
     * CEF and the folder of their session, and they change files only in the folder of their session. Turn it off only
     * if a page needs something it hides, such as fonts in the home folder.
     *
     * @param confined true to confine Chromium where the system can
     * @return this builder
     */
    public Builder confinement(final boolean confined) {
      this.confined = confined;
      return this;
    }

    /**
     * Names more hosts whose addresses pages may not reach, such as the public name or address of the machine the
     * server runs on. The guard refuses private addresses and every address of the machine's network interfaces
     * already; inside a container, the public address of the machine around it is no interface of the container,
     * but still reaches the services that listen on every interface of that machine, past a firewall in front of it.
     * The names are resolved when a browser starts. None by default, and refusing them only matters while pages may
     * not reach private networks.
     *
     * @param refusedHosts the names or addresses, each without a comma or a space
     * @return this builder
     * @throws IllegalArgumentException if a host is empty or holds a comma or a space
     */
    public Builder refusedHosts(final Collection<String> refusedHosts) {
      for (final String host : refusedHosts) {
        Preconditions.checkArgument(HelperConfiguration.isHost(host), "Not a host name or address: '%s'", host);
      }
      this.refusedHosts = List.copyOf(refusedHosts);
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     */
    public BrowserOptions build() {
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
}
