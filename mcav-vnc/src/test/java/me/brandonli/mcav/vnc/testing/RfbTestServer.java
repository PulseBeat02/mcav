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

import com.google.common.base.MoreObjects;
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
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.checkerframework.checker.nullness.qual.Nullable;

// A minimal RFB 3.8 (VNC) server on the loopback interface for tests. The server serves one client at a time. It
// answers every framebuffer update request with the whole screen as one raw-encoded rectangle filled with the current
// color, and records the key and pointer events it receives. Answers can be held back with #setAnswering(boolean), so a
// client can be observed before it has seen the size of the screen.
public final class RfbTestServer implements AutoCloseable {

  private static final byte[] VERSION = "RFB 003.008\n".getBytes(StandardCharsets.US_ASCII);

  private static final int SECURITY_NONE = 1;
  private static final int SECURITY_VNC = 2;
  private static final int SECURITY_UNSUPPORTED = 99;

  private static final String NAME = "mcav-test";

  private static final String ACCEPT_THREAD_NAME = "rfb-test-server";

  private static final int BACKLOG = 5;

  private static final long CLOSE_TIMEOUT_MILLISECONDS = 2_000L;

  private static final int SECURITY_TYPE_COUNT = 1;
  private static final int SECURITY_RESULT_OK = 0;
  private static final int SECURITY_RESULT_FAILED = 1;

  private static final int CHALLENGE_BYTES = 16;
  private static final byte CHALLENGE_FILL_BYTE = 7;

  private static final int DES_KEY_LENGTH = 8;
  private static final int MIRRORED_BYTE_SHIFT = 24;
  private static final String CIPHER_TRANSFORMATION = "DES/ECB/NoPadding";
  private static final String CIPHER_ALGORITHM = "DES";

  private static final int PADDING_BYTES = 3;

  private static final int BITS_PER_PIXEL = 32;
  private static final int DEPTH = 24;
  private static final int BITS_PER_BYTE = 8;
  private static final int BIG_ENDIAN_FLAG = 1;
  private static final int TRUE_COLOR_FLAG = 1;

  private static final int CHANNEL_MAXIMUM = 255;
  private static final int BYTE_MASK = 0xFF;

  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;
  private static final int BLUE_SHIFT = 0;

  private static final int INITIAL_COLOR = 0xFF0000;
  private static final int RGB_MASK = 0xFFFFFF;

  private static final int SET_PIXEL_FORMAT = 0;
  private static final int SET_ENCODINGS = 2;
  private static final int FRAMEBUFFER_UPDATE_REQUEST = 3;
  private static final int KEY_EVENT = 4;
  private static final int POINTER_EVENT = 5;
  private static final int CLIENT_CUT_TEXT = 6;

  private static final int ENCODING_LENGTH = 4;
  private static final int UPDATE_REQUEST_LENGTH = 9;

  private static final int FRAMEBUFFER_UPDATE = 0;
  private static final int UNKNOWN_MESSAGE_TYPE = 0x7F;

  private static final int RECTANGLE_COUNT = 1;
  private static final int RAW_ENCODING = 0;

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
    this.color = INITIAL_COLOR;
    this.layout = PixelLayout.SERVER;
    this.acceptThread = new Thread(this::acceptLoop, ACCEPT_THREAD_NAME);
    this.acceptThread.setDaemon(true);
  }

  public static RfbTestServer start(final int width, final int height) {
    return start(width, height, Security.NONE, "");
  }

  public static RfbTestServer start(final int width, final int height, final Security security, final String password) {
    Preconditions.checkArgument(width > 0, "Width %s must be positive!", width);
    Preconditions.checkArgument(height > 0, "Height %s must be positive!", height);
    Preconditions.checkNotNull(security, "Security must not be null!");
    Preconditions.checkNotNull(password, "Password must not be null!");

    try {
      final InetAddress loopback = InetAddress.getLoopbackAddress();
      final ServerSocket socket = new ServerSocket(0, BACKLOG, loopback);
      final RfbTestServer server = new RfbTestServer(socket, width, height, security, password);
      server.acceptThread.start();
      return server;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  // Stops accepting connections and waits up to two seconds for the connection being served to end.
  @Override
  public void close() {
    try {
      this.serverSocket.close();
      this.acceptThread.join(CLOSE_TIMEOUT_MILLISECONDS);
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  // Sends a message type the RFB protocol does not define, which makes a client fail.
  public void sendUnknownMessage() {
    final DataOutputStream current = this.output;
    if (current == null) {
      final String message = "No client is connected!";
      throw new IllegalStateException(message);
    }

    synchronized (this.outputLock) {
      try {
        current.writeByte(UNKNOWN_MESSAGE_TYPE);
        current.flush();
      } catch (final IOException exception) {
        throw new UncheckedIOException(exception);
      }
    }
  }

  private void acceptLoop() {
    while (!this.serverSocket.isClosed()) {
      this.acceptConnection();
    }
  }

  private void acceptConnection() {
    try (final Socket client = this.serverSocket.accept()) {
      this.connections.incrementAndGet();

      if (this.security == Security.HANG_UP) {
        return;
      }

      this.serve(client);
    } catch (final IOException exception) {
      // the client went away or the server was closed
    } finally {
      this.output = null;
    }
  }

  private void serve(final Socket client) throws IOException {
    final InputStream rawInput = client.getInputStream();
    final OutputStream rawOutput = client.getOutputStream();
    final BufferedInputStream bufferedInput = new BufferedInputStream(rawInput);
    final BufferedOutputStream bufferedOutput = new BufferedOutputStream(rawOutput);
    final DataInputStream input = new DataInputStream(bufferedInput);
    final DataOutputStream clientOutput = new DataOutputStream(bufferedOutput);
    clientOutput.write(VERSION);
    clientOutput.flush();
    final byte[] clientVersion = new byte[VERSION.length];
    input.readFully(clientVersion);

    final boolean accepted = this.negotiateSecurity(input, clientOutput);
    if (!accepted) {
      this.drain(input);
      return;
    }

    input.readUnsignedByte();
    this.layout = PixelLayout.SERVER;
    this.writeServerInitialization(clientOutput);
    this.output = clientOutput;
    try {
      this.readMessages(input);
    } finally {
      this.disconnected.countDown();
    }
  }

  private boolean negotiateSecurity(final DataInputStream input, final DataOutputStream clientOutput) throws IOException {
    return switch (this.security) {
      case UNSUPPORTED -> this.refuseSecurity(clientOutput);
      case PASSWORD -> this.negotiatePassword(input, clientOutput);
      case NONE, HANG_UP -> this.acceptWithoutSecurity(input, clientOutput);
    };
  }

  private void offerSecurityType(final DataOutputStream clientOutput, final int type) throws IOException {
    clientOutput.writeByte(SECURITY_TYPE_COUNT);
    clientOutput.writeByte(type);
    clientOutput.flush();
  }

  private boolean refuseSecurity(final DataOutputStream clientOutput) throws IOException {
    this.offerSecurityType(clientOutput, SECURITY_UNSUPPORTED);

    return false;
  }

  private boolean acceptWithoutSecurity(final DataInputStream input, final DataOutputStream clientOutput) throws IOException {
    this.offerSecurityType(clientOutput, SECURITY_NONE);
    input.readUnsignedByte();
    clientOutput.writeInt(SECURITY_RESULT_OK);
    clientOutput.flush();

    return true;
  }

  private boolean negotiatePassword(final DataInputStream input, final DataOutputStream clientOutput) throws IOException {
    this.offerSecurityType(clientOutput, SECURITY_VNC);
    input.readUnsignedByte();
    final byte[] challenge = new byte[CHALLENGE_BYTES];
    Arrays.fill(challenge, CHALLENGE_FILL_BYTE);
    clientOutput.write(challenge);
    clientOutput.flush();

    final byte[] response = new byte[CHALLENGE_BYTES];
    input.readFully(response);
    final byte[] expected = this.encryptChallenge(challenge, this.password);
    final boolean correct = Arrays.equals(expected, response);
    this.authenticated.set(correct);
    if (!correct) {
      final byte[] reason = "Wrong password".getBytes(StandardCharsets.US_ASCII);
      clientOutput.writeInt(SECURITY_RESULT_FAILED);
      clientOutput.writeInt(reason.length);
      clientOutput.write(reason);
      clientOutput.flush();

      return false;
    }

    clientOutput.writeInt(SECURITY_RESULT_OK);
    clientOutput.flush();

    return true;
  }

  private byte[] encryptChallenge(final byte[] challenge, final String secret) {
    final byte[] passwordBytes = secret.getBytes(StandardCharsets.US_ASCII);
    final byte[] key = new byte[DES_KEY_LENGTH];
    final int copied = Math.min(passwordBytes.length, key.length);
    System.arraycopy(passwordBytes, 0, key, 0, copied);
    // VNC authentication uses the bits of every key byte in reverse order
    for (int index = 0; index < key.length; index++) {
      final int keyByte = key[index] & BYTE_MASK;
      final int reversed = Integer.reverse(keyByte) >>> MIRRORED_BYTE_SHIFT;
      key[index] = (byte) reversed;
    }

    try {
      final Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
      final SecretKeySpec keySpecification = new SecretKeySpec(key, CIPHER_ALGORITHM);
      cipher.init(Cipher.ENCRYPT_MODE, keySpecification);
      return cipher.doFinal(challenge);
    } catch (final GeneralSecurityException exception) {
      throw new AssertionError(exception);
    }
  }

  private void writeServerInitialization(final DataOutputStream clientOutput) throws IOException {
    clientOutput.writeShort(this.width);
    clientOutput.writeShort(this.height);
    clientOutput.writeByte(BITS_PER_PIXEL);
    clientOutput.writeByte(DEPTH);
    clientOutput.writeByte(BIG_ENDIAN_FLAG);
    clientOutput.writeByte(TRUE_COLOR_FLAG);
    clientOutput.writeShort(CHANNEL_MAXIMUM);
    clientOutput.writeShort(CHANNEL_MAXIMUM);
    clientOutput.writeShort(CHANNEL_MAXIMUM);
    clientOutput.writeByte(RED_SHIFT);
    clientOutput.writeByte(GREEN_SHIFT);
    clientOutput.writeByte(BLUE_SHIFT);
    final byte[] padding = new byte[PADDING_BYTES];
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
      case SET_PIXEL_FORMAT -> this.readPixelFormat(input);
      case SET_ENCODINGS -> this.skipEncodings(input);
      case FRAMEBUFFER_UPDATE_REQUEST -> this.readUpdateRequest(input);
      case KEY_EVENT -> this.readKeyEvent(input);
      case POINTER_EVENT -> this.readPointerEvent(input);
      case CLIENT_CUT_TEXT -> this.skipCutText(input);
      default -> {
        final String message = "Client message %s is unknown!".formatted(type);
        throw new IOException(message);
      }
    }
  }

  private void discard(final DataInputStream input, final int count) throws IOException {
    final byte[] skipped = new byte[count];
    input.readFully(skipped);
  }

  private void skipEncodings(final DataInputStream input) throws IOException {
    input.readUnsignedByte();
    final int count = input.readUnsignedShort();
    this.discard(input, count * ENCODING_LENGTH);
  }

  private void skipCutText(final DataInputStream input) throws IOException {
    this.discard(input, PADDING_BYTES);
    final int length = input.readInt();
    this.discard(input, length);
  }

  private void readUpdateRequest(final DataInputStream input) throws IOException {
    this.discard(input, UPDATE_REQUEST_LENGTH);
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
    final int keySymbol = input.readInt();
    final ReceivedKey key = new ReceivedKey(keySymbol, down);
    synchronized (this.keys) {
      this.keys.add(key);
    }
  }

  private void readPointerEvent(final DataInputStream input) throws IOException {
    final int mask = input.readUnsignedByte();
    final int screenX = input.readUnsignedShort();
    final int screenY = input.readUnsignedShort();
    final ReceivedPointer pointer = new ReceivedPointer(mask, screenX, screenY);
    synchronized (this.pointers) {
      this.pointers.add(pointer);
    }
  }

  private void readPixelFormat(final DataInputStream input) throws IOException {
    this.discard(input, PADDING_BYTES);
    final int bitsPerPixel = input.readUnsignedByte();
    input.readUnsignedByte();
    final boolean bigEndian = input.readUnsignedByte() != 0;
    input.readUnsignedByte();
    final int redMaximum = input.readUnsignedShort();
    final int greenMaximum = input.readUnsignedShort();
    final int blueMaximum = input.readUnsignedShort();
    final int redShift = input.readUnsignedByte();
    final int greenShift = input.readUnsignedByte();
    final int blueShift = input.readUnsignedByte();
    this.discard(input, PADDING_BYTES);
    this.layout = new PixelLayout(
      bitsPerPixel / BITS_PER_BYTE,
      bigEndian,
      redMaximum,
      greenMaximum,
      blueMaximum,
      redShift,
      greenShift,
      blueShift
    );
  }

  private void answerPendingRequests() {
    final DataOutputStream currentOutput = this.output;
    if (currentOutput == null) {
      return;
    }

    try {
      this.writePendingUpdates(currentOutput);
    } catch (final IOException exception) {
      // the client went away
    }
  }

  private void writePendingUpdates(final DataOutputStream currentOutput) throws IOException {
    synchronized (this.outputLock) {
      while (this.pendingRequests > 0) {
        this.pendingRequests--;
        this.writeUpdate(currentOutput);
      }
    }
  }

  private void writeUpdate(final DataOutputStream clientOutput) throws IOException {
    final PixelLayout current = this.layout;
    final byte[] pixel = current.encode(this.color);
    clientOutput.writeByte(FRAMEBUFFER_UPDATE);
    clientOutput.writeByte(0);
    clientOutput.writeShort(RECTANGLE_COUNT);
    clientOutput.writeShort(0);
    clientOutput.writeShort(0);
    clientOutput.writeShort(this.width);
    clientOutput.writeShort(this.height);
    clientOutput.writeInt(RAW_ENCODING);
    final int count = this.width * this.height;
    for (int index = 0; index < count; index++) {
      clientOutput.write(pixel);
    }
    clientOutput.flush();
    this.updatesSent.incrementAndGet();
  }

  private void drain(final InputStream input) {
    final OutputStream discarded = OutputStream.nullOutputStream();
    try {
      input.transferTo(discarded);
    } catch (final IOException exception) {
      // the client hung up
    }
  }

  public int getPort() {
    return this.serverSocket.getLocalPort();
  }

  public void setColor(final int rgb) {
    this.color = rgb & RGB_MASK;
  }

  // Sets whether update requests are answered. Requests received while not answering are answered once answering is
  // enabled again.
  public void setAnswering(final boolean answering) {
    this.answering.set(answering);
    if (answering) {
      this.answerPendingRequests();
    }
  }

  public List<ReceivedKey> getKeys() {
    synchronized (this.keys) {
      return List.copyOf(this.keys);
    }
  }

  public List<ReceivedPointer> getPointers() {
    synchronized (this.pointers) {
      return List.copyOf(this.pointers);
    }
  }

  public int getKeyCount() {
    synchronized (this.keys) {
      return this.keys.size();
    }
  }

  public int getPointerCount() {
    synchronized (this.pointers) {
      return this.pointers.size();
    }
  }

  public int getUpdatesSent() {
    return this.updatesSent.get();
  }

  public int getConnections() {
    return this.connections.get();
  }

  public boolean isAuthenticated() {
    return this.authenticated.get();
  }

  public CountDownLatch getDisconnected() {
    return this.disconnected;
  }

  public enum Security {
    NONE,
    PASSWORD,
    UNSUPPORTED,
    // Closes every connection right after accepting it, before the protocol version is sent.
    HANG_UP,
  }

  private static final class PixelLayout {

    private static final PixelLayout SERVER = new PixelLayout(
      BITS_PER_PIXEL / BITS_PER_BYTE,
      true,
      CHANNEL_MAXIMUM,
      CHANNEL_MAXIMUM,
      CHANNEL_MAXIMUM,
      RED_SHIFT,
      GREEN_SHIFT,
      BLUE_SHIFT
    );

    private final int bytesPerPixel;
    private final boolean bigEndian;
    private final int redMaximum;
    private final int greenMaximum;
    private final int blueMaximum;
    private final int redShift;
    private final int greenShift;
    private final int blueShift;

    PixelLayout(
      final int bytesPerPixel,
      final boolean bigEndian,
      final int redMaximum,
      final int greenMaximum,
      final int blueMaximum,
      final int redShift,
      final int greenShift,
      final int blueShift
    ) {
      this.bytesPerPixel = bytesPerPixel;
      this.bigEndian = bigEndian;
      this.redMaximum = redMaximum;
      this.greenMaximum = greenMaximum;
      this.blueMaximum = blueMaximum;
      this.redShift = redShift;
      this.greenShift = greenShift;
      this.blueShift = blueShift;
    }

    // Encodes a color as one pixel of this layout in the byte order negotiated by SetPixelFormat.
    byte[] encode(final int rgb) {
      final int red = (rgb >> RED_SHIFT) & BYTE_MASK;
      final int green = (rgb >> GREEN_SHIFT) & BYTE_MASK;
      final int blue = (rgb >> BLUE_SHIFT) & BYTE_MASK;
      final long redBits = this.scaleChannel(red, this.redMaximum, this.redShift);
      final long greenBits = this.scaleChannel(green, this.greenMaximum, this.greenShift);
      final long blueBits = this.scaleChannel(blue, this.blueMaximum, this.blueShift);
      final long value = redBits | greenBits | blueBits;

      final byte[] bytes = new byte[this.bytesPerPixel];
      for (int index = 0; index < bytes.length; index++) {
        final int byteIndex = this.bigEndian ? bytes.length - 1 - index : index;
        final int shift = byteIndex * BITS_PER_BYTE;
        bytes[index] = (byte) (value >>> shift);
      }

      return bytes;
    }

    private long scaleChannel(final int channel, final int maximum, final int shift) {
      final int scaled = (channel * maximum) / CHANNEL_MAXIMUM;
      return (long) scaled << shift;
    }
  }

  public static final class ReceivedKey {

    private final int keySymbol;
    private final boolean down;

    ReceivedKey(final int keySymbol, final boolean down) {
      this.keySymbol = keySymbol;
      this.down = down;
    }

    public int getKeySymbol() {
      return this.keySymbol;
    }

    public boolean isDown() {
      return this.down;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof final ReceivedKey key)) {
        return false;
      }

      return this.keySymbol == key.keySymbol && this.down == key.down;
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.keySymbol, this.down);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("keySymbol", this.keySymbol).add("down", this.down).toString();
    }
  }

  public static final class ReceivedPointer {

    private final int buttonMask;
    private final int screenX;
    private final int screenY;

    ReceivedPointer(final int buttonMask, final int screenX, final int screenY) {
      this.buttonMask = buttonMask;
      this.screenX = screenX;
      this.screenY = screenY;
    }

    public int getButtonMask() {
      return this.buttonMask;
    }

    public int getX() {
      return this.screenX;
    }

    public int getY() {
      return this.screenY;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof final ReceivedPointer pointer)) {
        return false;
      }

      return this.buttonMask == pointer.buttonMask && this.screenX == pointer.screenX && this.screenY == pointer.screenY;
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.buttonMask, this.screenX, this.screenY);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
        .add("buttonMask", this.buttonMask)
        .add("screenX", this.screenX)
        .add("screenY", this.screenY)
        .toString();
    }
  }
}
