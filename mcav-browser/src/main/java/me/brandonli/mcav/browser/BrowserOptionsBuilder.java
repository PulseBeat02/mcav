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

import java.util.Collection;

/**
 * Builds immutable {@link BrowserOptions}, starting from the defaults of {@link BrowserOptions#DEFAULT}.
 *
 * <p>Builders are mutable and not thread-safe; use one per thread. {@link #build()} checks the values and snapshots
 * them, so a builder may go on to build other options.
 *
 * @since 1.0.0
 */
public interface BrowserOptionsBuilder {
  /**
   * Sets how many frames per second the browser paints at most.
   *
   * @param frameRate the frame rate, from 1 to {@value BrowserOptions#MAXIMUM_FRAME_RATE}
   * @return this builder
   * @since 1.0.0
   */
  BrowserOptionsBuilder frameRate(final int frameRate);

  /**
   * Turns V8's just-in-time compiler on or off. It is off by default, because the pages run without the Chromium
   * sandbox; turn it on only for pages you trust.
   *
   * @param allowsJavaScriptJit true to compile JavaScript to machine code
   * @return this builder
   * @since 1.0.0
   */
  BrowserOptionsBuilder javaScriptJit(final boolean allowsJavaScriptJit);

  /**
   * Lets pages reach loopback, private, link-local and other non-public addresses, such as a dashboard in the server's
   * own network. Off by default: a page, or a player clicking on it, could otherwise read services the server can reach
   * but the players cannot.
   *
   * @param allowsPrivateNetworks true to let the browser connect to any address
   * @return this builder
   * @since 1.0.0
   */
  BrowserOptionsBuilder privateNetworks(final boolean allowsPrivateNetworks);

  /**
   * Lets pages play sound right away. Off by default: as in a desktop browser, a page plays sound only once someone
   * clicked or typed into it, such as a player who clicks the screen of the browser.
   *
   * @param allowsAutoplay true to let pages play sound before anyone clicked or typed into them
   * @return this builder
   * @since 1.0.0
   */
  BrowserOptionsBuilder autoplay(final boolean allowsAutoplay);

  /**
   * Confines Chromium on Linux and macOS, or lets it run as before. On by default: Chromium's processes then cannot
   * read the server's folder, the home folder of the server's user or the server's temporary folder, apart from Java,
   * CEF and the folder of their session, and they change files only in the folder of their session. Turn it off only
   * if a page needs something it hides, such as fonts in the home folder.
   *
   * @param confined true to confine Chromium where the system can
   * @return this builder
   * @since 1.0.0
   */
  BrowserOptionsBuilder confinement(final boolean confined);

  /**
   * Names more hosts whose addresses pages may not reach, such as the public name or address of the machine the server
   * runs on. The guard refuses private addresses and every address of the machine's network interfaces already; inside
   * a container, the public address of the machine around it is no interface of the container, but still reaches the
   * services that listen on every interface of that machine, past a firewall in front of it. The names are resolved
   * when a browser starts. None by default, and refusing them only matters while pages may not reach private networks.
   *
   * @param refusedHosts the names or addresses, each without a comma or a space
   * @return this builder
   * @throws NullPointerException if {@code refusedHosts} or one of its hosts is null
   * @since 1.0.0
   */
  BrowserOptionsBuilder refusedHosts(final Collection<String> refusedHosts);

  /**
   * Returns the options built so far.
   *
   * @return the options
   * @throws IllegalArgumentException if the frame rate is out of range, or a host is empty or holds a comma or a space
   * @since 1.0.0
   */
  BrowserOptions build();
}
