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
package me.brandonli.mcav;

import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.module.MCAVModule;

/**
 * The entry point of the library.
 *
 * <p>Obtain an instance with {@link MCAV#api()}, install it once at startup, and release it at shutdown.
 *
 * <p><b>Lifecycle.</b> {@link #install(Class[])} returns as soon as the library is usable: the JavaCV natives are
 * loaded, the requested modules are started, and the lookup tables are built, which takes a few seconds on the first
 * start of a machine and less afterward. {@link Capability#FFMPEG} and {@link Capability#FACE_DETECTION} are decided
 * by then. The external programs behind {@link Capability#VLC} and {@link Capability#YT_DLP} are prepared in the
 * background afterward: found on the system, found in the cache folder of the library, or downloaded into it without
 * administrator rights. Each of these capabilities turns on when its program is ready, which can take several minutes
 * the first time on a slow connection. Until then:
 * <ul>
 *   <li>{@link #hasCapability(Capability)} reports the capability as unavailable;
 *   <li>{@link #whenCapabilityReady(Capability)} returns a future that completes once the preparation finished;
 *   <li>the features behind the capability refuse with an {@link IllegalStateException} that says the program is
 *   still being prepared: {@link me.brandonli.mcav.media.player.multimedia.VideoPlayer#vlc(String...)} and the default
 *   {@link me.brandonli.mcav.json.ytdlp.YTDLPParser}.
 * </ul>
 * {@link #release()} cancels a preparation that is still running.
 *
 * <pre><code>
 *   final MCAVApi api = MCAV.api();
 *   api.install();
 *   // FFmpeg players can be used right away
 *   final CompletableFuture&lt;Boolean&gt; vlcReady = api.whenCapabilityReady(Capability.VLC);
 *   vlcReady.thenAccept(available -&gt; {
 *     // VLC players can be created from now on if available is true
 *   });
 *   // at shutdown
 *   api.release();
 * </code></pre>
 */
public interface MCAVApi {
  /**
   * Checks whether an optional capability is available right now, which depends on the platform and on whether the
   * program behind it could be installed. A capability that is still being prepared in the background is not
   * available yet; wait for it with {@link #whenCapabilityReady(Capability)}.
   *
   * @param capability the capability to check
   * @return true if the capability is available
   * @throws MCAVLoadingException if the library has not been installed yet
   */
  boolean hasCapability(final Capability capability);

  /**
   * Gets a future that completes once a capability is ready, without polling. It completes with true when the
   * capability is available, and with false when it cannot be provided: the system does not support it, its
   * installation failed, or {@link #release()} cancelled the installation first. Once the future completed with
   * true, {@link #hasCapability(Capability)} reports the capability as available.
   *
   * <p>A capability that is not prepared in the background, or whose preparation already finished, gets a future
   * that is complete already. Callbacks attached to the future never run on the installation threads of the library,
   * so they may call any method of the library, including {@link #release()}.
   *
   * @param capability the capability to wait for
   * @return a future that completes with whether the capability is available
   * @throws MCAVLoadingException if the library has not been installed yet
   */
  CompletableFuture<Boolean> whenCapabilityReady(final Capability capability);

  /**
   * Installs the library and starts the specified modules. This method returns once the JavaCV natives are loaded,
   * the modules are started, and the lookup tables are built; VLC and yt-dlp are prepared in the background
   * afterward, see {@link #whenCapabilityReady(Capability)}.
   *
   * <p>It may be called once per release: calling it again while the library is installed, or while another thread
   * is installing it, fails. After {@link #release()}, or after an installation that failed, the library can be
   * installed again.
   *
   * @param modules the module classes to start, which must implement {@link MCAVModule}
   * @throws MCAVLoadingException if the library cannot be installed, is installed already, or is being installed by
   *                              another thread
   */
  void install(final Class<?>... modules);

  /**
   * Stops all modules and releases the resources of the library. Media players that are still running should be
   * released before calling this method.
   *
   * <p>If another thread is installing the library, this method waits for that installation to finish and then
   * releases it. A background preparation of VLC or yt-dlp that is still running is cancelled rather than waited for:
   * its download is interrupted, its temporary file is deleted, and its thread has ended when this method returns,
   * unless it ignores the interruption for more than ten seconds, which is logged. The futures of
   * {@link #whenCapabilityReady(Capability)} for the cancelled programs complete with false. Releasing a library that
   * is not installed does nothing.
   */
  void release();

  /**
   * Gets a module that was started by {@link #install(Class[])}.
   *
   * @param moduleClass the class of the module
   * @param <T>         the type of the module
   * @return the module instance
   * @throws me.brandonli.mcav.module.ModuleException if the module was not installed
   */
  <T extends MCAVModule> T getModule(final Class<T> moduleClass);
}
