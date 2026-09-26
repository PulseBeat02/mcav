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
package me.brandonli.mcav.bukkit.media.mcv2;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timestamp;

/**
 * One frame a screen's channel sent, for Java Flight Recorder: its ids, its size, when its pages left, and what the
 * viewers' links did with it. The channel records it for every frame, whether the frame came from the encoder or from a
 * pre-encoded stream; it costs nothing unless a recording is running.
 */
@Name("me.brandonli.mcav.Mcv2Send")
@Label("MCV2 Send")
@Category({ "mcav", "MCV2" })
@Description("A frame an MCV2 channel sent: when, how large, and which viewers took it or were held back")
final class Mcv2SendEvent extends Event {

  @Label("Frame Id")
  long frameId;

  @Label("Reference Id")
  long referenceId;

  @Label("Keyframe")
  boolean keyframe;

  @Label("Bytes")
  @DataAmount
  int bytes;

  @Label("Map Colours")
  @Description("The map colours of the frame's pages, or -1 when it had more pages than the screen has slots")
  int colors;

  @Label("Sent")
  @Timestamp(Timestamp.MILLISECONDS_SINCE_EPOCH)
  long sent;

  @Label("Viewers Sent")
  int sentTo;

  @Label("Viewers Behind")
  @Description("Viewers held back because their connection was over the backlog limit")
  int behind;

  @Label("Viewers Waiting")
  @Description("Viewers held back because they did not hold the frame's reference")
  int waiting;

  @Label("Largest Backlog")
  @DataAmount
  long backlog;
}
