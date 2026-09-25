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
package me.brandonli.mcav.browser;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * A misbehaving browser helper for tests of the server's session: it reads the configuration like the real helper,
 * connects, and then does what the path of the page says:
 *
 * <ul>
 *   <li>{@code /wrong-token}: introduces itself with another token;</li>
 *   <li>{@code /wrong-version}: introduces itself with another protocol version;</li>
 *   <li>{@code /no-hello}: sends a notice first;</li>
 *   <li>{@code /server-message}: introduces itself, is ready and loaded, then sends a close, which only the server
 *   sends;</li>
 *   <li>{@code /wrong-page}: introduces itself and sends a frame of a page of another size;</li>
 *   <li>{@code /stall}: shows the page but never reads what the server sends;</li>
 *   <li>{@code /stubborn}: shows the page and ignores the end of its standard input;</li>
 *   <li>{@code /frame-first}: sends a frame before it introduces itself;</li>
 *   <li>{@code /hang-up}: shows the page, then closes the connection but keeps running;</li>
 *   <li>{@code /exit}: exits before it connects.</li>
 * </ul>
 *
 * <p>It then waits until its standard input ends.
 */
public final class RawHelperMain {

  private RawHelperMain() {}

  /**
   * Runs the misbehaving helper.
   *
   * @param args ignored
   * @throws IOException if the connection fails
   */
  public static void main(final String[] args) throws IOException {
    final BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    final HelperConfiguration configuration = HelperConfiguration.fromLine(input.readLine());
    if (configuration.getUrl().getPath().equals("/exit")) {
      System.exit(4);
    }
    final SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    channel.connect(UnixDomainSocketAddress.of(configuration.getSocket()));
    final DataOutputStream out = new DataOutputStream(Channels.newOutputStream(channel));
    final String path = configuration.getUrl().getPath();
    final byte[] token = configuration.getToken();
    switch (path) {
      case "/wrong-token" -> {
        token[0] ^= 1;
        HelperProtocol.writeHello(out, token);
      }
      case "/wrong-version" -> {
        out.writeByte(HelperProtocol.HELLO);
        out.writeInt(HelperProtocol.TOKEN_BYTES + 2);
        out.write(token);
        out.writeShort(HelperProtocol.VERSION + 1);
      }
      case "/no-hello" -> HelperProtocol.writeText(out, HelperProtocol.NOTICE, "hi");
      case "/server-message" -> {
        HelperProtocol.writeHello(out, token);
        HelperProtocol.writeText(out, HelperProtocol.READY, "raw");
        HelperProtocol.writeFrame(out, new FrameRegion(configuration.getWidth(), configuration.getHeight(), 0, 0, 1, 1, new byte[4]));
        HelperProtocol.writeLoading(out, false);
        out.flush();
        sleep();
        HelperProtocol.writeClose(out);
      }
      case "/stall", "/stubborn", "/hang-up" -> {
        HelperProtocol.writeHello(out, token);
        HelperProtocol.writeText(out, HelperProtocol.READY, "raw");
        HelperProtocol.writeFrame(out, new FrameRegion(configuration.getWidth(), configuration.getHeight(), 0, 0, 1, 1, new byte[4]));
        HelperProtocol.writeLoading(out, false);
        out.flush();
        if (path.equals("/hang-up")) {
          channel.close();
        }
        if (path.equals("/stubborn")) {
          while (true) {
            sleep();
          }
        }
      }
      case "/frame-first" -> HelperProtocol.writeFrame(
        out,
        new FrameRegion(configuration.getWidth(), configuration.getHeight(), 0, 0, 1, 1, new byte[4])
      );
      case "/wrong-page" -> {
        HelperProtocol.writeHello(out, token);
        HelperProtocol.writeFrame(out, new FrameRegion(configuration.getWidth() + 1, configuration.getHeight(), 0, 0, 1, 1, new byte[4]));
      }
      default -> throw new IllegalArgumentException("Unknown scenario " + path);
    }
    out.flush();
    while (input.read() >= 0) {
      // wait until the server is done with this helper
    }
    channel.close();
  }

  private static void sleep() {
    try {
      Thread.sleep(500L);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }
}
