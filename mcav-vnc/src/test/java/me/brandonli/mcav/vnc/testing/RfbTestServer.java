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
package me.brandonli.mcav.vnc.testing;

import com.google.common.base.Preconditions;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * A minimal RFB 3.8 (VNC) server on the loopback interface for tests.
 *
 * <p>The server serves one client at a time. It answers every framebuffer update request with the whole screen as
 * one raw-encoded rectangle filled with the current color, and records the key and pointer events it receives.
 * Answers can be held back with {@link #setAnswering(boolean)}, so a client can be observed before it has seen the
 * size of the screen.
 */
public final class RfbTestServer implements AutoCloseable {

  private static final byte[] VERSION = "RFB 003.008\n".getBytes(StandardCharsets.US_ASCII);
  private static final int SECURITY_NONE = 1;
  private static final int SECURITY_VNC = 2;
  private static final int SECURITY_UNSUPPORTED = 99;
  private static final String NAME = "mcav-test";

  /**
   * How the server treats the security handshake.
   */
  public enum Security {
    /**
     * Offers no authentication.
     */
    NONE,
    /**
     * Requires VNC authentication with the password given to {@link #start(int, int, Security, String)}.
     */
    PASSWORD,
    /**
     * Only offers a security type no client knows.
     */
    UNSUPPORTED,
    /**
     * Closes every connection right after accepting it, before the protocol version is sent.
     */
    HANG_UP,
  }

  private final ServerSocket serverSocket;
  private final int width;
  private final int height;
  private final Security security;
  private final String password;
  private final List<ReceivedKey> keys;
  private final List<ReceivedPointer> pointers;
  private final AtomicInteger updatesSent;
  private final AtomicInteger connections;
  private final AtomicBoolean answering;
  private final AtomicBoolean authenticated;
  private final CountDownLatch disconnected;
  private final Object outputLock;
  private final Thread acceptThread;

  private volatile int color;
  private volatile int pendingRequests;
  private volatile PixelLayout layout;
  private volatile DataOutputStream output;

  private RfbTestServer(
    final ServerSocket serverSocket,
    final int width,
    final int height,
    final Security security,
    final String password
  ) {
    this.serverSocket = serverSocket;
    this.width = width;
    this.height = height;
    this.security = security;
    this.password = password;
    this.keys = new ArrayList<>();
    this.pointers = new ArrayList<>();
    this.updatesSent = new AtomicInteger();
    this.connections = new AtomicInteger();
    this.answering = new AtomicBoolean(true);
    this.authenticated = new AtomicBoolean(false);
    this.disconnected = new CountDownLatch(1);
    this.outputLock = new Object();
    this.color = 0xFF0000;
    this.layout = PixelLayout.SERVER;
    this.acceptThread = new Thread(this::acceptLoop, "rfb-test-server");
    this.acceptThread.setDaemon(true);
  }

  /**
   * Starts a server without authentication.
   *
   * @param width  the width of the remote screen
   * @param height the height of the remote screen
   * @return the running server
   */
  public static RfbTestServer start(final int width, final int height) {
    return start(width, height, Security.NONE, "");
  }

  /**
   * Starts a server.
   *
   * @param width    the width of the remote screen
   * @param height   the height of the remote screen
   * @param security how the security handshake is answered
   * @param password the password required by {@link Security#PASSWORD}
   * @return the running server
   */
  public static RfbTestServer start(final int width, final int height, final Security security, final String password) {
    Preconditions.checkArgument(width > 0, "Width must be positive but was %s", width);
    Preconditions.checkArgument(height > 0, "Height must be positive but was %s", height);
    Preconditions.checkNotNull(security, "Security must not be null");
    Preconditions.checkNotNull(password, "Password must not be null");
    try {
      final InetAddress loopback = InetAddress.getLoopbackAddress();
      final ServerSocket socket = new ServerSocket(0, 5, loopback);
      final RfbTestServer server = new RfbTestServer(socket, width, height, security, password);
      server.acceptThread.start();
      return server;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Gets the port the server listens on.
   *
   * @return the port
   */
  public int getPort() {
    return this.serverSocket.getLocalPort();
  }

  /**
   * Sets the color the screen is filled with from the next update on.
   *
   * @param rgb the color as packed RGB
   */
  public void setColor(final int rgb) {
    this.color = rgb & 0xFFFFFF;
  }

  /**
   * Sets whether update requests are answered. Requests received while not answering are answered once answering
   * is enabled again.
   *
   * @param answer true to answer requests
   */
  public void setAnswering(final boolean answer) {
    this.answering.set(answer);
    if (answer) {
      this.answerPendingRequests();
    }
  }

  /**
   * Sends a message type the RFB protocol does not define, which makes a client fail.
   */
  public void sendUnknownMessage() {
    final DataOutputStream current = this.output;
    if (current == null) {
      throw new IllegalStateException("No client is connected");
    }
    synchronized (this.outputLock) {
      try {
        current.writeByte(0x7F);
        current.flush();
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    }
  }

  /**
   * Gets the key events received so far.
   *
   * @return a copy of the key events in the order they arrived
   */
  public List<ReceivedKey> getKeys() {
    synchronized (this.keys) {
      return List.copyOf(this.keys);
    }
  }

  /**
   * Gets the pointer events received so far.
   *
   * @return a copy of the pointer events in the order they arrived
   */
  public List<ReceivedPointer> getPointers() {
    synchronized (this.pointers) {
      return List.copyOf(this.pointers);
    }
  }

  /**
   * Gets how many key events were received so far.
   *
   * @return the number of key events
   */
  public int getKeyCount() {
    synchronized (this.keys) {
      return this.keys.size();
    }
  }

  /**
   * Gets how many pointer events were received so far.
   *
   * @return the number of pointer events
   */
  public int getPointerCount() {
    synchronized (this.pointers) {
      return this.pointers.size();
    }
  }

  /**
   * Gets how many framebuffer updates were sent.
   *
   * @return the number of updates
   */
  public int getUpdatesSent() {
    return this.updatesSent.get();
  }

  /**
   * Gets how many connections were accepted.
   *
   * @return the number of connections
   */
  public int getConnections() {
    return this.connections.get();
  }

  /**
   * Checks whether a client passed VNC authentication with the right password.
   *
   * @return true if the right password was sent
   */
  public boolean isAuthenticated() {
    return this.authenticated.get();
  }

  /**
   * Gets the latch that opens once the first client disconnects after the handshake.
   *
   * @return the latch
   */
  public CountDownLatch getDisconnected() {
    return this.disconnected;
  }

  private void acceptLoop() {
    while (!this.serverSocket.isClosed()) {
      try (final Socket client = this.serverSocket.accept()) {
        this.connections.incrementAndGet();
        if (this.security == Security.HANG_UP) {
          continue;
        }
        this.serve(client);
      } catch (final IOException | GeneralSecurityException exception) {
        // the client went away or the server was closed
      } finally {
        this.output = null;
      }
    }
  }

  private void serve(final Socket client) throws IOException, GeneralSecurityException {
    final InputStream rawInput = client.getInputStream();
    final OutputStream rawOutput = client.getOutputStream();
    final BufferedInputStream bufferedInput = new BufferedInputStream(rawInput);
    final BufferedOutputStream bufferedOutput = new BufferedOutputStream(rawOutput);
    final DataInputStream input = new DataInputStream(bufferedInput);
    final DataOutputStream clientOutput = new DataOutputStream(bufferedOutput);
    clientOutput.write(VERSION);
    clientOutput.flush();
    final byte[] clientVersion = new byte[12];
    input.readFully(clientVersion);

    final boolean accepted = this.negotiateSecurity(input, clientOutput);
    if (!accepted) {
      drain(input);
      return;
    }

    input.readUnsignedByte();
    this.layout = PixelLayout.SERVER;
    this.writeServerInit(clientOutput);
    this.output = clientOutput;
    try {
      this.readMessages(input);
    } finally {
      this.disconnected.countDown();
    }
  }

  private boolean negotiateSecurity(final DataInputStream input, final DataOutputStream clientOutput)
    throws IOException, GeneralSecurityException {
    return switch (this.security) {
      case UNSUPPORTED -> refuseSecurity(clientOutput);
      case PASSWORD -> this.negotiatePassword(input, clientOutput);
      default -> acceptWithoutSecurity(input, clientOutput);
    };
  }

  private static void offerSecurityType(final DataOutputStream clientOutput, final int type) throws IOException {
    clientOutput.writeByte(1);
    clientOutput.writeByte(type);
    clientOutput.flush();
  }

  private static boolean refuseSecurity(final DataOutputStream clientOutput) throws IOException {
    offerSecurityType(clientOutput, SECURITY_UNSUPPORTED);
    return false;
  }

  private static boolean acceptWithoutSecurity(final DataInputStream input, final DataOutputStream clientOutput) throws IOException {
    offerSecurityType(clientOutput, SECURITY_NONE);
    input.readUnsignedByte();
    clientOutput.writeInt(0);
    clientOutput.flush();
    return true;
  }

  private boolean negotiatePassword(final DataInputStream input, final DataOutputStream clientOutput)
    throws IOException, GeneralSecurityException {
    offerSecurityType(clientOutput, SECURITY_VNC);
    input.readUnsignedByte();
    final byte[] challenge = new byte[16];
    Arrays.fill(challenge, (byte) 7);
    clientOutput.write(challenge);
    clientOutput.flush();

    final byte[] response = new byte[16];
    input.readFully(response);
    final byte[] expected = encryptChallenge(challenge, this.password);
    final boolean correct = Arrays.equals(expected, response);
    this.authenticated.set(correct);
    if (!correct) {
      final byte[] reason = "Wrong password".getBytes(StandardCharsets.US_ASCII);
      clientOutput.writeInt(1);
      clientOutput.writeInt(reason.length);
      clientOutput.write(reason);
      clientOutput.flush();
      return false;
    }

    clientOutput.writeInt(0);
    clientOutput.flush();
    return true;
  }

  private static byte[] encryptChallenge(final byte[] challenge, final String secret) throws GeneralSecurityException {
    final byte[] passwordBytes = secret.getBytes(StandardCharsets.US_ASCII);
    final byte[] key = new byte[8];
    final int copied = Math.min(passwordBytes.length, key.length);
    System.arraycopy(passwordBytes, 0, key, 0, copied);
    // VNC authentication uses the bits of every key byte in reverse order
    for (int index = 0; index < key.length; index++) {
      final int keyByte = key[index] & 0xFF;
      final int reversed = Integer.reverse(keyByte) >>> 24;
      key[index] = (byte) reversed;
    }

    final Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
    final SecretKeySpec keySpec = new SecretKeySpec(key, "DES");
    cipher.init(Cipher.ENCRYPT_MODE, keySpec);
    return cipher.doFinal(challenge);
  }

  private void writeServerInit(final DataOutputStream clientOutput) throws IOException {
    clientOutput.writeShort(this.width);
    clientOutput.writeShort(this.height);
    clientOutput.writeByte(32);
    clientOutput.writeByte(24);
    clientOutput.writeByte(1);
    clientOutput.writeByte(1);
    clientOutput.writeShort(255);
    clientOutput.writeShort(255);
    clientOutput.writeShort(255);
    clientOutput.writeByte(16);
    clientOutput.writeByte(8);
    clientOutput.writeByte(0);
    final byte[] padding = new byte[3];
    clientOutput.write(padding);
    final byte[] name = NAME.getBytes(StandardCharsets.US_ASCII);
    clientOutput.writeInt(name.length);
    clientOutput.write(name);
    clientOutput.flush();
  }

  private void readMessages(final DataInputStream input) throws IOException {
    while (true) {
      final int type = input.read();
      if (type == -1) {
        return;
      }
      this.readMessage(input, type);
    }
  }

  private void readMessage(final DataInputStream input, final int type) throws IOException {
    switch (type) {
      case 0 -> this.readPixelFormat(input);
      case 2 -> skipEncodings(input);
      case 3 -> this.readUpdateRequest(input);
      case 4 -> this.readKeyEvent(input);
      case 5 -> this.readPointerEvent(input);
      case 6 -> skipCutText(input);
      default -> throw new IOException("Unknown client message " + type);
    }
  }

  private static void discard(final DataInputStream input, final int count) throws IOException {
    final byte[] skipped = new byte[count];
    input.readFully(skipped);
  }

  private static void skipEncodings(final DataInputStream input) throws IOException {
    input.readUnsignedByte();
    final int count = input.readUnsignedShort();
    discard(input, count * 4);
  }

  private static void skipCutText(final DataInputStream input) throws IOException {
    discard(input, 3);
    final int length = input.readInt();
    discard(input, length);
  }

  private void readUpdateRequest(final DataInputStream input) throws IOException {
    discard(input, 9);
    synchronized (this.outputLock) {
      this.pendingRequests++;
    }
    final boolean answerNow = this.answering.get();
    if (answerNow) {
      this.answerPendingRequests();
    }
  }

  private void readKeyEvent(final DataInputStream input) throws IOException {
    final boolean down = input.readBoolean();
    input.readUnsignedShort();
    final int keysym = input.readInt();
    final ReceivedKey key = new ReceivedKey(keysym, down);
    synchronized (this.keys) {
      this.keys.add(key);
    }
  }

  private void readPointerEvent(final DataInputStream input) throws IOException {
    final int mask = input.readUnsignedByte();
    final int x = input.readUnsignedShort();
    final int y = input.readUnsignedShort();
    final ReceivedPointer pointer = new ReceivedPointer(mask, x, y);
    synchronized (this.pointers) {
      this.pointers.add(pointer);
    }
  }

  private void readPixelFormat(final DataInputStream input) throws IOException {
    discard(input, 3);
    final int bitsPerPixel = input.readUnsignedByte();
    input.readUnsignedByte();
    final boolean bigEndian = input.readUnsignedByte() != 0;
    input.readUnsignedByte();
    final int redMax = input.readUnsignedShort();
    final int greenMax = input.readUnsignedShort();
    final int blueMax = input.readUnsignedShort();
    final int redShift = input.readUnsignedByte();
    final int greenShift = input.readUnsignedByte();
    final int blueShift = input.readUnsignedByte();
    discard(input, 3);
    this.layout = new PixelLayout(bitsPerPixel / 8, bigEndian, redMax, greenMax, blueMax, redShift, greenShift, blueShift);
  }

  private void answerPendingRequests() {
    final DataOutputStream currentOutput = this.output;
    if (currentOutput == null) {
      return;
    }
    synchronized (this.outputLock) {
      try {
        while (this.pendingRequests > 0) {
          this.pendingRequests--;
          this.writeUpdate(currentOutput);
        }
      } catch (final IOException exception) {
        // the client went away
      }
    }
  }

  private void writeUpdate(final DataOutputStream clientOutput) throws IOException {
    final PixelLayout current = this.layout;
    final byte[] pixel = current.encode(this.color);
    clientOutput.writeByte(0);
    clientOutput.writeByte(0);
    clientOutput.writeShort(1);
    clientOutput.writeShort(0);
    clientOutput.writeShort(0);
    clientOutput.writeShort(this.width);
    clientOutput.writeShort(this.height);
    clientOutput.writeInt(0);
    final int count = this.width * this.height;
    for (int index = 0; index < count; index++) {
      clientOutput.write(pixel);
    }
    clientOutput.flush();
    this.updatesSent.incrementAndGet();
  }

  private static void drain(final InputStream input) {
    final OutputStream discarded = OutputStream.nullOutputStream();
    try {
      // discard until the client hangs up
      input.transferTo(discarded);
    } catch (final IOException exception) {
      // the client hung up
    }
  }

  /**
   * Stops accepting connections and waits up to two seconds for the connection being served to end.
   */
  @Override
  public void close() {
    try {
      this.serverSocket.close();
      this.acceptThread.join(2_000L);
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  /**
   * The pixel format a client asked for.
   */
  private static final class PixelLayout {

    static final PixelLayout SERVER = new PixelLayout(4, true, 255, 255, 255, 16, 8, 0);

    private final int bytesPerPixel;
    private final boolean bigEndian;
    private final int redMax;
    private final int greenMax;
    private final int blueMax;
    private final int redShift;
    private final int greenShift;
    private final int blueShift;

    PixelLayout(
      final int bytesPerPixel,
      final boolean bigEndian,
      final int redMax,
      final int greenMax,
      final int blueMax,
      final int redShift,
      final int greenShift,
      final int blueShift
    ) {
      this.bytesPerPixel = bytesPerPixel;
      this.bigEndian = bigEndian;
      this.redMax = redMax;
      this.greenMax = greenMax;
      this.blueMax = blueMax;
      this.redShift = redShift;
      this.greenShift = greenShift;
      this.blueShift = blueShift;
    }

    /**
     * Encodes a color as one pixel of this layout in the byte order negotiated by SetPixelFormat.
     *
     * @param rgb the color as packed RGB
     * @return the bytes of the pixel
     */
    byte[] encode(final int rgb) {
      final int red = (rgb >> 16) & 0xFF;
      final int green = (rgb >> 8) & 0xFF;
      final int blue = rgb & 0xFF;
      final long redBits = scaleChannel(red, this.redMax, this.redShift);
      final long greenBits = scaleChannel(green, this.greenMax, this.greenShift);
      final long blueBits = scaleChannel(blue, this.blueMax, this.blueShift);
      final long value = redBits | greenBits | blueBits;

      final byte[] bytes = new byte[this.bytesPerPixel];
      for (int index = 0; index < bytes.length; index++) {
        final int byteIndex = this.bigEndian ? bytes.length - 1 - index : index;
        final int shift = byteIndex * 8;
        bytes[index] = (byte) (value >>> shift);
      }
      return bytes;
    }

    private static long scaleChannel(final int channel, final int max, final int shift) {
      final int scaled = (channel * max) / 255;
      return (long) scaled << shift;
    }
  }

  /**
   * A key event a client sent.
   */
  public static final class ReceivedKey {

    private final int keysym;
    private final boolean down;

    ReceivedKey(final int keysym, final boolean down) {
      this.keysym = keysym;
      this.down = down;
    }

    /**
     * Gets the keysym.
     *
     * @return the keysym
     */
    public int getKeysym() {
      return this.keysym;
    }

    /**
     * Checks whether the key was pressed or released.
     *
     * @return true if pressed
     */
    public boolean isDown() {
      return this.down;
    }

    @Override
    public String toString() {
      final String state = this.down ? "down " : "up ";
      final String code = Integer.toHexString(this.keysym);
      return state + code;
    }
  }

  /**
   * A pointer event a client sent.
   */
  public static final class ReceivedPointer {

    private final int buttonMask;
    private final int x;
    private final int y;

    ReceivedPointer(final int buttonMask, final int x, final int y) {
      this.buttonMask = buttonMask;
      this.x = x;
      this.y = y;
    }

    /**
     * Gets the pressed buttons, bit 0 being the left button.
     *
     * @return the button mask
     */
    public int getButtonMask() {
      return this.buttonMask;
    }

    /**
     * Gets the x coordinate on the remote screen.
     *
     * @return the x coordinate
     */
    public int getX() {
      return this.x;
    }

    /**
     * Gets the y coordinate on the remote screen.
     *
     * @return the y coordinate
     */
    public int getY() {
      return this.y;
    }

    @Override
    public String toString() {
      return "pointer " + this.buttonMask + " at " + this.x + "," + this.y;
    }
  }
}
