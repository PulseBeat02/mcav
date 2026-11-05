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

import java.awt.Dimension;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.VideoApi;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter;

// VLC's decoded size can include padding, so the visible size comes from the video track; the VideoRenderer chooses the buffer.
final class FrameFormatCallback extends BufferFormatCallbackAdapter {

  private final VideoRenderer renderer;
  private final MediaPlayer player;

  FrameFormatCallback(final VideoRenderer renderer, final MediaPlayer player) {
    this.renderer = renderer;
    this.player = player;
  }

  // The visible size is the video track's, limited to the decoded size, which also stands in when the track reports no usable size.
  @Override
  public BufferFormat getBufferFormat(final int sourceWidth, final int sourceHeight) {
    final VideoApi videoApi = this.player.video();
    final Dimension trackSize = videoApi.videoDimension();
    int visibleWidth = sourceWidth;
    int visibleHeight = sourceHeight;
    if (trackSize != null && Math.min(trackSize.width, trackSize.height) > 0) {
      visibleWidth = Math.min(sourceWidth, trackSize.width);
      visibleHeight = Math.min(sourceHeight, trackSize.height);
    }

    return this.renderer.createBufferFormat(sourceWidth, sourceHeight, visibleWidth, visibleHeight);
  }
}
