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
package me.brandonli.mcav.sandbox.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.DumpUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link DumpCommand}.
 */
final class DumpCommandTest {

  @Test
  void sendsTheLinkOnceTheDumpIsUploaded() {
    final CommandSender sender = mock(CommandSender.class);
    final DumpCommand command = new DumpCommand(() -> "https://paste.helpch.at/abc");
    command.createDump(sender);
    final Component loading = Message.CREATE_DUMP.build();
    final Component link = Message.SEND_DUMP.build("https://paste.helpch.at/abc");
    verify(sender, timeout(10_000)).sendMessage(link);
    final InOrder order = Mockito.inOrder(sender);
    order.verify(sender).sendMessage(loading);
    order.verify(sender).sendMessage(link);
  }

  @Test
  void tellsTheSenderWhenTheUploadFails() throws InterruptedException {
    final CommandSender sender = mock(CommandSender.class);
    final CountDownLatch attempted = new CountDownLatch(1);
    final DumpCommand command = new DumpCommand(() -> {
      attempted.countDown();
      throw new IllegalStateException("offline");
    });
    command.createDump(sender);
    final boolean ran = attempted.await(10, TimeUnit.SECONDS);
    assertTrue(ran);
    final Component loading = Message.CREATE_DUMP.build();
    final Component failure = Message.DUMP_FAILED.build();
    verify(sender, timeout(10_000)).sendMessage(failure);
    final InOrder order = Mockito.inOrder(sender);
    order.verify(sender).sendMessage(loading);
    order.verify(sender).sendMessage(failure);
    verify(sender, times(2)).sendMessage(any(Component.class));
  }

  @Test
  void uploadsWithTheDumpUtilitiesByDefault() throws ReflectiveOperationException {
    final DumpCommand command = new DumpCommand();
    final Field field = DumpCommand.class.getDeclaredField("uploader");
    field.setAccessible(true);
    final Object value = field.get(command);
    final Supplier<?> uploader = (Supplier<?>) value;
    try (final MockedStatic<DumpUtils> dumps = Mockito.mockStatic(DumpUtils.class)) {
      dumps.when(DumpUtils::createAndUploadDump).thenReturn("https://paste.helpch.at/xyz");
      final Object url = uploader.get();
      assertEquals("https://paste.helpch.at/xyz", url);
      dumps.verify(DumpUtils::createAndUploadDump, times(1));
    }
  }

  @Test
  void refusesNullArguments() {
    final DumpCommand command = new DumpCommand(() -> "unused");
    assertThrows(NullPointerException.class, () -> new DumpCommand(null));
    assertThrows(NullPointerException.class, () -> command.createDump(null));
  }
}
