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
package me.brandonli.mcav.json.ytdlp.format;

import java.net.URI;
import java.util.ArrayList;
import me.brandonli.mcav.media.source.UriSource;

public class Format {

  public String format_id;
  public String format_note;
  public String ext;
  public String protocol;
  public String acodec;
  public String vcodec;
  public String url;
  public int width;
  public int height;
  public double fps;
  public int rows;
  public int columns;
  public ArrayList<Fragment> fragments;
  public String resolution;
  public double aspect_ratio;
  public int filesize_approx;
  public HttpHeaders http_headers;
  public String audio_ext;
  public String video_ext;
  public double vbr;
  public double abr;
  public double tbr;
  public String format;
  public Object format_index;
  public String manifest_url;
  public String language;
  public Object preference;
  public double quality;
  public boolean has_drm;
  public int source_preference;
  public int asr;
  public int filesize;
  public int audio_channels;
  public int language_preference;
  public String dynamic_range;
  public String container;
  public DownloaderOptions downloader_options;

  public UriSource toUriSource() {
    return UriSource.uri(URI.create(this.url));
  }
}
