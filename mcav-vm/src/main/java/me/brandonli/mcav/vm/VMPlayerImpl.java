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
package me.brandonli.mcav.vm;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.LockUtils;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.vnc.VNCPlayer;
import me.brandonli.mcav.vnc.VNCSource;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An implementation of the {@link VMPlayer} interface that provides VM support.
 */
public class VMPlayerImpl implements VMPlayer {

  private final Lock lock;

  @Nullable private volatile VNCPlayer vncPlayer;

  @Nullable private volatile VMProcess process;

  VMPlayerImpl() {
    this.lock = new ReentrantLock();
    this.vncPlayer = VNCPlayer.vm();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(
    final VideoPipelineStep step,
    final VMSettings settings,
    final Architecture architecture,
    final VMConfiguration arguments
  ) {
    return LockUtils.executeWithLock(this.lock, () -> {
      final VMProcess process = new VMProcess(settings, architecture, arguments);
      process.start();
      this.process = process;
      final VNCSource source = this.getVncSource(settings);
      final VNCPlayer vncPlayer = requireNonNull(this.vncPlayer);
      return vncPlayer.start(step, source);
    });
  }

  private VNCSource getVncSource(final VMSettings settings) {
    final int vncPort = settings.getPort();
    final int width = settings.getWidth();
    final int height = settings.getHeight();
    final int targetFps = settings.getTargetFps();
    return VNCSource.vnc().host("localhost").port(vncPort).screenWidth(width).screenHeight(height).targetFrameRate(targetFps).build();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void moveMouse(final int x, final int y) {
    LockUtils.executeWithLock(this.lock, () -> {
      if (this.vncPlayer != null) {
        final VNCPlayer player = requireNonNull(this.vncPlayer);
        player.moveMouse(x, y);
      }
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void sendMouseEvent(final MouseClick type, final int x, final int y) {
    LockUtils.executeWithLock(this.lock, () -> {
      if (this.vncPlayer != null) {
        final VNCPlayer player = requireNonNull(this.vncPlayer);
        player.sendMouseEvent(type, x, y);
      }
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void sendKeyEvent(final String text) {
    LockUtils.executeWithLock(this.lock, () -> {
      if (this.vncPlayer != null) {
        final VNCPlayer player = requireNonNull(this.vncPlayer);
        player.sendKeyEvent(text);
      }
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean release() {
    return LockUtils.executeWithLock(this.lock, () -> {
      if (this.vncPlayer != null) {
        final VNCPlayer player = requireNonNull(this.vncPlayer);
        player.release();
        this.vncPlayer = null;
      }
      if (this.process != null) {
        final VMProcess process = requireNonNull(this.process);
        process.shutdown();
        this.process = null;
      }
      return true;
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean pause() {
    return LockUtils.executeWithLock(this.lock, () -> {
      if (this.vncPlayer == null) {
        return false;
      }
      final VNCPlayer player = requireNonNull(this.vncPlayer);
      return player.pause();
    });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean resume() {
    return LockUtils.executeWithLock(this.lock, () -> {
      if (this.vncPlayer == null) {
        return false;
      }
      final VNCPlayer player = requireNonNull(this.vncPlayer);
      return player.resume();
    });
  }
}
