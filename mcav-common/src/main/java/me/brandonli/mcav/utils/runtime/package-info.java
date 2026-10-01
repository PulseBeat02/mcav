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
 * Runs external programs synchronously while capturing standard output and error independently.
 *
 * <p>{@link me.brandonli.mcav.utils.runtime.CommandTask} uses separate command arguments without shell parsing:
 * pass each token separately and do not add shell quoting. The command list is copied. A task is mutable and not
 * thread-safe; one successful launch consumes it even if waiting subsequently fails. A failed launch leaves it
 * retryable. Captured streams are decoded as UTF-8 and retained in memory, so output should be bounded by the program.
 *
 * <p>Timeouts cover process exit and both captured streams. Timeout or interruption requests forcible termination
 * of the process and observed descendants; interruption is restored before an I/O failure is reported. Termination
 * is a request, not a guarantee that all descendants have exited. A returned process handle is borrowed for
 * inspection or application-directed control and should not be used to race the task's output readers.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.utils.runtime;
