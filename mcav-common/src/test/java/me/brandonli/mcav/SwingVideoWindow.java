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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;

/**
 * A window that shows frames, shared by the examples. The {@link #asFilter()} method turns it into a video filter
 * that can be attached at the end of a pipeline.
 */
public final class SwingVideoWindow {

  private final JLabel label;

  public SwingVideoWindow(final String title, final int width, final int height) {
    final JFrame frame = new JFrame(title);
    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    frame.setSize(width, height);
    this.label = new JLabel();
    final Dimension size = new Dimension(width, height);
    this.label.setPreferredSize(size);
    this.label.setHorizontalAlignment(JLabel.CENTER);
    this.label.setVerticalAlignment(JLabel.CENTER);
    this.label.setBackground(Color.BLACK);
    this.label.setOpaque(true);
    frame.setLayout(new BorderLayout());
    frame.add(this.label, BorderLayout.CENTER);
    frame.setVisible(true);
  }

  public VideoFilter asFilter() {
    return (samples, _) -> {
      this.show(samples);
      // the frame is only read, so the filter reports that it left the samples untouched
      return false;
    };
  }

  private void show(final ImageBuffer samples) {
    final BufferedImage image = samples.toBufferedImage();
    final ImageIcon icon = new ImageIcon(image);
    SwingUtilities.invokeLater(() -> this.label.setIcon(icon));
  }
}
