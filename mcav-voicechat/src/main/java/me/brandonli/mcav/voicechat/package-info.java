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
 * Outputs positional pipeline audio through Simple Voice Chat.
 *
 * <p>Install {@link me.brandonli.mcav.voicechat.SVCModule} and inject the server's
 * {@link de.maxhenkel.voicechat.api.VoicechatServerApi} before creating an
 * {@link me.brandonli.mcav.voicechat.SVCFilter}. Each supplied platform entity becomes a speaker. The filter downmixes
 * 48 kHz signed 16-bit stereo PCM to mono and queues complete 20 millisecond frames for each speaker.
 * See {@link me.brandonli.mcav.voicechat.SVCFilter} for a pipeline example.
 *
 * <p>Start the filter before feeding it, then release it to stop speakers and close their Opus encoders.
 * The caller owns the entities and voice chat API. Module shutdown clears the shared API reference but does
 * not release existing filters. The default filter synchronizes lifecycle and producer calls; speaker queues
 * are independently synchronized for voice chat consumers.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.voicechat;
