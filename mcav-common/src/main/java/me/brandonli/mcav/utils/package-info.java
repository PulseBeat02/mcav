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
 * Provides shared file, archive, source-classification, metadata and cleanup helpers.
 *
 * <p>{@link me.brandonli.mcav.utils.IoUtils} holds the file, hashing, network and archive helpers,
 * {@link me.brandonli.mcav.utils.SourceUtils} classifies strings and sources,
 * {@link me.brandonli.mcav.utils.MetadataUtils} reads media properties with FFmpeg,
 * {@link me.brandonli.mcav.utils.ExecutorUtils} shuts executors down and {@link me.brandonli.mcav.utils.ThrowableUtils}
 * serves code that catches failures at a thread boundary.
 *
 * <p>File and metadata operations are synchronous and can block on disk, network or native decoding. I/O helpers report
 * failures as a {@link me.brandonli.mcav.utils.FileOperationException}, metadata reads as an
 * {@link me.brandonli.mcav.utils.InputMetadataException} and unsafe zip entries as a
 * {@link me.brandonli.mcav.utils.ZipEntryIntegrityException}. Callers close returned streams and readers. Cache
 * downloads persist on disk, and probing a free port does not reserve it for a later bind.
 *
 * <p>Static helpers retain no caller-owned mutable state, but concurrent changes to the same files or archives
 * need caller coordination. Archive extraction can leave files written before a failure and requires a destination
 * that is not being modified concurrently. Executor shutdown transfers the executor into shutdown state and
 * cannot guarantee that a task obeys interruption.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable}.
 *
 * @since 1.0.0
 */
package me.brandonli.mcav.utils;
