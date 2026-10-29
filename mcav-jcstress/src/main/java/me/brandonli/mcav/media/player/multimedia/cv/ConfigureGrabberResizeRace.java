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
package me.brandonli.mcav.media.player.multimedia.cv;

import java.nio.file.Path;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * The players of OpenCV and FFmpeg tell a new grabber the size to scale to while that size changes. Pass 1 made
 * {@link AbstractVideoPlayerCv#configureGrabber} read the attached size once instead of asking the callback twice, and
 * that is what this holds on to: a size read twice lets a change in between give the grabber the width of one size and
 * the height of the other. The actor runs the real method of a real player on a grabber that decodes nothing, which
 * needs no native library; the copier that scales frames on the decoding thread reads the same callback, but writes
 * into OpenCV matrices, so {@code VideoFrameCopierResizeStressTest} in mcav-common covers it instead. The result is
 * the size the grabber was told.
 */
@JCStressTest
@Outcome(id = "320, 240", expect = Expect.ACCEPTABLE, desc = "the size before the change")
@Outcome(id = "640, 480", expect = Expect.ACCEPTABLE, desc = "the size after the change")
@Outcome(expect = Expect.FORBIDDEN, desc = "the width of one size and the height of the other")
@State
public class ConfigureGrabberResizeRace {

  private static final Path SOURCE_PATH = Path.of("race.mp4");
  private static final Source SOURCE = FileSource.path(SOURCE_PATH);

  private static final int TARGET_WIDTH = 320;
  private static final int TARGET_HEIGHT = 240;

  private static final int LARGER_WIDTH = 640;
  private static final int LARGER_HEIGHT = 480;

  private final ConfiguringPlayer player;
  private final DimensionAttachableCallback dimensionCallback;
  private final IdleGrabber grabber;

  /**
   * Creates a player whose target size is attached.
   */
  public ConfigureGrabberResizeRace() {
    final Dimension target = Dimension.of(TARGET_WIDTH, TARGET_HEIGHT);
    this.player = new ConfiguringPlayer();
    this.dimensionCallback = this.player.getDimensionAttachableCallback();
    this.dimensionCallback.attach(target);

    this.grabber = new IdleGrabber();
  }

  /**
   * Configures the grabber as the player does before it starts one.
   *
   * @param outcome the width and height the grabber was told to scale to
   */
  @Actor
  public void configure(final II_Result outcome) {
    this.player.configure(this.grabber, SOURCE);
    outcome.r1 = this.grabber.getImageWidth();
    outcome.r2 = this.grabber.getImageHeight();
  }

  /**
   * Attaches another size.
   */
  @Actor
  public void resize() {
    final Dimension larger = Dimension.of(LARGER_WIDTH, LARGER_HEIGHT);
    this.dimensionCallback.attach(larger);
  }

  private static final class ConfiguringPlayer extends AbstractVideoPlayerCv {

    private void configure(final FrameGrabber grabber, final Source source) {
      this.configureGrabber(grabber, source);
    }

    @Override
    protected FrameGrabber createFrameGrabber(final String resource) {
      final String message = "This player only configures grabbers!";
      throw new UnsupportedOperationException(message);
    }
  }

  private static final class IdleGrabber extends FrameGrabber {

    @Override
    public void start() {}

    @Override
    public void stop() {}

    @Override
    public void trigger() {}

    @Override
    public Frame grab() {
      final String message = "This grabber decodes nothing!";
      throw new UnsupportedOperationException(message);
    }

    @Override
    public void release() {}
  }
}
