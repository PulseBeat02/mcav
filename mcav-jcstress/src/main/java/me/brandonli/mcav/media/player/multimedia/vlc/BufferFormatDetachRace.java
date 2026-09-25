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
package me.brandonli.mcav.media.player.multimedia.vlc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;

/**
 * VLC asks the renderer for the buffer it renders into while the size the frames are scaled to is detached. Pass 1
 * found that {@code createBufferFormat} asked the callback whether it was attached and then asked it for the size,
 * so a detach in between answered with the empty fallback and asked for a buffer of 0x0, which vlcj refuses with an
 * exception inside the callback of VLC; it now reads the size once. The result is the size of the buffer VLC is told
 * to render into.
 */
@JCStressTest
@Outcome(id = "320, 240", expect = Expect.ACCEPTABLE, desc = "the size was read before the detach: frames are scaled to it")
@Outcome(id = "640, 480", expect = Expect.ACCEPTABLE, desc = "the size was read after the detach: frames keep their own size")
@Outcome(id = "0, 0", expect = Expect.FORBIDDEN, desc = "a buffer of 0x0, which vlcj refuses inside the VLC callback")
@Outcome(expect = Expect.FORBIDDEN, desc = "any other buffer size")
@State
public class BufferFormatDetachRace {

  private static final int SOURCE_WIDTH = 640;
  private static final int SOURCE_HEIGHT = 480;

  private final DimensionAttachableCallback dimensionCallback;
  private final VideoRenderer renderer;

  /**
   * Creates a renderer whose target size is attached.
   */
  public BufferFormatDetachRace() {
    final Dimension target = Dimension.of(320, 240);
    this.dimensionCallback = DimensionAttachableCallback.create();
    this.dimensionCallback.attach(target);
    final VideoPlayerMultiplexer owner = createOwner(this.dimensionCallback);
    this.renderer = new VideoRenderer(owner);
  }

  private static VideoPlayerMultiplexer createOwner(final DimensionAttachableCallback dimensionCallback) {
    final ClassLoader loader = VideoPlayerMultiplexer.class.getClassLoader();
    final Class<?>[] interfaces = { VideoPlayerMultiplexer.class };
    final InvocationHandler handler = (final Object proxy, final Method method, final Object[] arguments) -> {
      final String name = method.getName();
      return name.equals("getDimensionAttachableCallback") ? dimensionCallback : null;
    };
    return (VideoPlayerMultiplexer) Proxy.newProxyInstance(loader, interfaces, handler);
  }

  /**
   * The VLC callback that chooses the buffer.
   *
   * @param outcome the width and the height of the buffer
   */
  @Actor
  public void chooseBuffer(final II_Result outcome) {
    try {
      final BufferFormat format = this.renderer.createBufferFormat(SOURCE_WIDTH, SOURCE_HEIGHT, SOURCE_WIDTH, SOURCE_HEIGHT);
      outcome.r1 = format.getWidth();
      outcome.r2 = format.getHeight();
    } catch (final IllegalArgumentException emptyBuffer) {
      // vlcj refuses a buffer of 0x0 as soon as it is created, inside the callback of VLC: that is how the bug shows
      outcome.r1 = 0;
      outcome.r2 = 0;
    }
  }

  /**
   * The application detaching the size, as a player does when its output goes away.
   */
  @Actor
  public void detach() {
    this.dimensionCallback.detach();
  }
}
