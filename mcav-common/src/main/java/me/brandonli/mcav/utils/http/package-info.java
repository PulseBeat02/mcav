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
/**
 * Downloads HTTP resources and performs small network probes.
 *
 * <p>{@link me.brandonli.mcav.utils.http.HttpDownloader} file methods create unique temporary files, verify
 * optional hashes and replace destinations only after completion. They make up to three attempts for retryable
 * failures, with two- and four-second delays, and bound stalled body reads to one minute. HTTP 408, 429 and 5xx
 * responses are retryable; other status failures, oversized bodies and checksum mismatches are not.
 *
 * <p>Stream and text helpers make a single request and do not apply the file downloader's retry or idle-read
 * policy. Close returned streams and clients; text helpers close their own clients. Operations block and should
 * run off latency-sensitive threads. File replacements for equal normalized paths are serialized inside the JVM,
 * but callers still coordinate with external writers. Network probes return snapshots, not future reachability.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.utils.http;
