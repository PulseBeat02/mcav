/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.media.player.pipeline.filter.video;

import me.brandonli.mcav.media.image.MatImageBuffer;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

public class OverlayImageFilter extends MatVideoFilter {

  private final int x;
  private final int y;
  private final Mat overlayMat;
  private final int overlayWidth;
  private final int overlayHeight;

  public OverlayImageFilter(final MatImageBuffer overlay, final int x, final int y) {
    this.x = x;
    this.y = y;
    this.overlayMat = overlay.getOrThrow(MatImageBuffer.MAT_PROPERTY);
    this.overlayWidth = this.overlayMat.cols();
    this.overlayHeight = this.overlayMat.rows();
  }

  @Override
  void modifyMat(final Mat mat) {
    final int width = Math.min(this.overlayWidth, mat.cols() - this.x);
    final int height = Math.min(this.overlayHeight, mat.rows() - this.y);
    if (width <= 0 || height <= 0) {
      return;
    }
    final Rect roi = new Rect(this.x, this.y, width, height);
    final Mat submat = mat.submat(roi);
    final Mat overlaySubmat = this.overlayMat.submat(0, height, 0, width);
    Core.addWeighted(submat, 1.0, overlaySubmat, 1.0, 0.0, submat);
  }
}
