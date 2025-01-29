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

import java.util.ArrayList;

/**
 * This class is used to parse the output of the yt-dlp extractor.
 */
public class URLParseDump {

  /**
   * Default constructor for URLParseDump.
   */
  public URLParseDump() {
    // no-op
  }

  /** id **/
  public String id;
  /** title **/
  public String title;
  /** formats **/
  public ArrayList<Format> formats;
  /** thumbnails **/
  public ArrayList<Thumbnail> thumbnails;
  /** thumbnail **/
  public String thumbnail;
  /** description **/
  public String description;
  /** channel_id **/
  public String channel_id;
  /** channel_url **/
  public String channel_url;
  /** duration **/
  public int duration;
  /** view_count **/
  public int view_count;
  /** average_rating **/
  public Object average_rating;
  /** age_limit **/
  public int age_limit;
  /** webpage_url **/
  public String webpage_url;
  /** categories **/
  public ArrayList<String> categories;
  /** tags **/
  public ArrayList<String> tags;
  /** playable_in_embed **/
  public boolean playable_in_embed;
  /** live_status **/
  public String live_status;
  /** release_timestamp **/
  public Object release_timestamp;
  /** _format_sort_fields **/
  public ArrayList<String> _format_sort_fields;
  /** comment_count **/
  public int comment_count;
  /** chapters **/
  public Object chapters;
  /** heatmap **/
  public ArrayList<Heatmap> heatmap;
  /** like_count **/
  public int like_count;
  /** channel **/
  public String channel;
  /** channel_follower_count **/
  public int channel_follower_count;
  /** channel_is_verified **/
  public boolean channel_is_verified;
  /** uploader **/
  public String uploader;
  /** uploader_id **/
  public String uploader_id;
  /** uploader_url **/
  public String uploader_url;
  /** upload_date **/
  public String upload_date;
  /** timestamp **/
  public int timestamp;
  /** availability **/
  public String availability;
  /** original_url **/
  public String original_url;
  /** webpage_url_basename **/
  public String webpage_url_basename;
  /** webpage_url_domain **/
  public String webpage_url_domain;
  /** extractor **/
  public String extractor;
  /** extractor_key **/
  public String extractor_key;
  /** playlist **/
  public Object playlist;
  /** playlist_index **/
  public Object playlist_index;
  /** display_id **/
  public String display_id;
  /** fulltitle **/
  public String fulltitle;
  /** duration_string **/
  public String duration_string;
  /** release_year **/
  public Object release_year;
  /** is_live **/
  public boolean is_live;
  /** was_live **/
  public boolean was_live;
  /** requested_subtitles **/
  public Object requested_subtitles;
  /** _has_drm **/
  public Object _has_drm;
  /** epoch **/
  public int epoch;
  /** asr **/
  public int asr;
  /** filesize **/
  public Object filesize;
  /** format_id **/
  public String format_id;
  /** format_note **/
  public String format_note;
  /** source_preference **/
  public int source_preference;
  /** fps **/
  public int fps;
  /** audio_channels **/
  public int audio_channels;
  /** height **/
  public int height;
  /** quality **/
  public double quality;
  /** has_drm **/
  public boolean has_drm;
  /** tbr **/
  public double tbr;
  /** filesize_approx **/
  public int filesize_approx;
  /** url **/
  public String url;
  /** width **/
  public int width;
  /** language **/
  public String language;
  /** language_preference **/
  public int language_preference;
  /** preference **/
  public Object preference;
  /** ext **/
  public String ext;
  /** vcodec **/
  public String vcodec;
  /** acodec **/
  public String acodec;
  /** dynamic_range **/
  public String dynamic_range;
  /** downloader_options **/
  public DownloaderOptions downloader_options;
  /** protocol **/
  public String protocol;
  /** resolution **/
  public String resolution;
  /** aspect_ratio **/
  public double aspect_ratio;
  /** http_headers **/
  public HttpHeaders http_headers;
  /** video_ext **/
  public String video_ext;
  /** audio_ext **/
  public String audio_ext;
  /** vbr **/
  public Object vbr;
  /** abr **/
  public Object abr;
  /** format **/
  public String format;
  /** _filename **/
  public String _filename;
  /** filename **/
  public String filename;
  /** _type **/
  public String _type;
  /** _version **/
  public Version _version;
}
