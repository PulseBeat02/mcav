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

import java.util.ArrayList;

/**
 * This class is used to parse the output of the yt-dlp extractor.
 */
public class URLParseDump {

  public String id;
  public String title;
  public ArrayList<Format> formats;
  public ArrayList<Thumbnail> thumbnails;
  public String thumbnail;
  public String description;
  public String channel_id;
  public String channel_url;
  public int duration;
  public int view_count;
  public Object average_rating;
  public int age_limit;
  public String webpage_url;
  public ArrayList<String> categories;
  public ArrayList<String> tags;
  public boolean playable_in_embed;
  public String live_status;
  public Object release_timestamp;
  public ArrayList<String> _format_sort_fields;
  public int comment_count;
  public Object chapters;
  public ArrayList<Heatmap> heatmap;
  public int like_count;
  public String channel;
  public int channel_follower_count;
  public boolean channel_is_verified;
  public String uploader;
  public String uploader_id;
  public String uploader_url;
  public String upload_date;
  public int timestamp;
  public String availability;
  public String original_url;
  public String webpage_url_basename;
  public String webpage_url_domain;
  public String extractor;
  public String extractor_key;
  public Object playlist;
  public Object playlist_index;
  public String display_id;
  public String fulltitle;
  public String duration_string;
  public Object release_year;
  public boolean is_live;
  public boolean was_live;
  public Object requested_subtitles;
  public Object _has_drm;
  public int epoch;
  public int asr;
  public Object filesize;
  public String format_id;
  public String format_note;
  public int source_preference;
  public int fps;
  public int audio_channels;
  public int height;
  public double quality;
  public boolean has_drm;
  public double tbr;
  public int filesize_approx;
  public String url;
  public int width;
  public String language;
  public int language_preference;
  public Object preference;
  public String ext;
  public String vcodec;
  public String acodec;
  public String dynamic_range;
  public DownloaderOptions downloader_options;
  public String protocol;
  public String resolution;
  public double aspect_ratio;
  public HttpHeaders http_headers;
  public String video_ext;
  public String audio_ext;
  public Object vbr;
  public Object abr;
  public String format;
  public String _filename;
  public String filename;
  public String _type;
  public Version _version;
}
