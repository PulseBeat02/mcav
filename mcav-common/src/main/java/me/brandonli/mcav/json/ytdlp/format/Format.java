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
package me.brandonli.mcav.json.ytdlp.format;

import java.net.URI;
import java.util.ArrayList;
import me.brandonli.mcav.media.source.uri.UriSource;

/** Format **/
public class Format {

  Format() {
    // no-op
  }

  /** format_id **/
  public String format_id;
  /** format_note **/
  public String format_note;
  /** ext **/
  public String ext;
  /** protocol **/
  public String protocol;
  /** acodec **/
  public String acodec;
  /** vcodec **/
  public String vcodec;
  /** url **/
  public String url;
  /** width **/
  public int width;
  /** height **/
  public int height;
  /** fps **/
  public double fps;
  /** rows **/
  public int rows;
  /** columns **/
  public int columns;
  /** fragments **/
  public ArrayList<Fragment> fragments;
  /** resolution **/
  public String resolution;
  /** aspect_ratio **/
  public double aspect_ratio;
  /** filesize_approx **/
  public int filesize_approx;
  /** http_headers **/
  public HttpHeaders http_headers;
  /** audio_ext **/
  public String audio_ext;
  /** video_ext **/
  public String video_ext;
  /** vbr **/
  public double vbr;
  /** abr **/
  public double abr;
  /** tbr **/
  public double tbr;
  /** format **/
  public String format;
  /** format_index **/
  public Object format_index;
  /** manifest_url **/
  public String manifest_url;
  /** language **/
  public String language;
  /** preference **/
  public Object preference;
  /** quality **/
  public double quality;
  /** has_drm **/
  public boolean has_drm;
  /** source_preference **/
  public int source_preference;
  /** asr **/
  public int asr;
  /** filesize **/
  public int filesize;
  /** audio_channels **/
  public int audio_channels;
  /** language_preference **/
  public int language_preference;
  /** dynamic_range **/
  public String dynamic_range;
  /** container **/
  public String container;
  /** downloader_options **/
  public DownloaderOptions downloader_options;

  /**
   * Converts this Format to a UriSource.
   *
   * @return a UriSource representing the URL of this format.
   */
  public UriSource toUriSource() {
    return UriSource.uri(URI.create(this.url));
  }
}
