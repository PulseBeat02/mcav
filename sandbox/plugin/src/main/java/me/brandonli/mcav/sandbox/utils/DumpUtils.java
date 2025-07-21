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
package me.brandonli.mcav.sandbox.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class DumpUtils {

  private DumpUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  private static final String FILE_UPLOAD_WEBSITE = "https://paste.helpch.at/";
  private static final String FILE_ENDPOINT_URL = "%sdocuments/".formatted(FILE_UPLOAD_WEBSITE);
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  public static String createAndUploadDump() {
    try {
      final String dump = createDumpContents();
      final String json = uploadDump(dump);
      final JsonElement jsonElement = JsonParser.parseString(json);
      final JsonObject jsonObject = jsonElement.getAsJsonObject();
      final JsonElement file = jsonObject.get("key");
      final String id = file.getAsString();
      return "%s%s".formatted(FILE_UPLOAD_WEBSITE, id);
    } catch (final IOException | InterruptedException e) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new AssertionError(e);
    }
  }

  private static String uploadDump(final String dump) throws IOException, InterruptedException {
    final URI uri = URI.create(FILE_ENDPOINT_URL);
    final HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.ofString(dump);
    final HttpRequest request = HttpRequest.newBuilder().uri(uri).header("Content-Type", "text/plain").POST(bodyPublisher).build();
    final HttpResponse.BodyHandler<String> responseBodyHandler = HttpResponse.BodyHandlers.ofString();
    final HttpResponse<String> response = HTTP_CLIENT.send(request, responseBodyHandler);
    if (response.statusCode() == 200) {
      return response.body();
    } else {
      final int code = response.statusCode();
      final String msg = "HTTP Error Code: %s".formatted(code);
      throw new IOException(msg);
    }
  }

  private static String createDumpContents() {
    final StringBuilder dump = new StringBuilder();

    dump.append("=== System Information ===\n");
    final String osName = System.getProperty("os.name");
    final String osVersion = System.getProperty("os.version");
    final String osArch = System.getProperty("os.arch");
    dump.append("OS: ").append(osName).append("\n");
    dump.append("OS Version: ").append(osVersion).append("\n");
    dump.append("OS Architecture: ").append(osArch).append("\n");

    final String javaVersion = System.getProperty("java.version");
    final String javaVendor = System.getProperty("java.vendor");
    dump.append("Java Version: ").append(javaVersion).append("\n");
    dump.append("Java Vendor: ").append(javaVendor).append("\n");

    final Runtime runtime = Runtime.getRuntime();
    final int availableProcessors = runtime.availableProcessors();
    dump.append("Available Processors: ").append(availableProcessors).append("\n");

    final long freeMemory = runtime.freeMemory();
    final long maxMemory = runtime.maxMemory();
    final long totalMemory = runtime.totalMemory();
    dump.append("Free Memory: ").append(freeMemory / 1024 / 1024).append(" MB\n");
    dump.append("Max Memory: ").append(maxMemory / 1024 / 1024).append(" MB\n");
    dump.append("Total Memory: ").append(totalMemory / 1024 / 1024).append(" MB\n");

    dump.append("\n=== Environment Variables ===\n");
    final Map<String, String> env = System.getenv();
    env.forEach((key, value) -> dump.append(key).append(": ").append(value).append("\n"));

    dump.append("\n=== JVM Properties ===\n");
    final Properties properties = System.getProperties();
    properties.forEach((key, value) -> dump.append(key).append(": ").append(value).append("\n"));

    dump.append("\n=== Thread Information ===\n");
    final Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
    stackTraces.forEach((thread, stackTrace) -> {
      dump.append("Thread Name: ").append(thread.getName()).append("\n");
      dump.append("State: ").append(thread.getState()).append("\n");
      dump.append("Stack Trace:\n");
      for (final StackTraceElement element : stackTrace) {
        dump.append("\t").append(element).append("\n");
      }
    });

    final RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
    final long uptime = bean.getUptime();
    final long startTime = bean.getStartTime();
    dump.append("\n=== Runtime Information ===\n");
    dump.append("Uptime: ").append(uptime).append(" ms\n");
    dump.append("Start Time: ").append(startTime).append(" ms since epoch\n");

    dump.append("\n=== User Logs ===\n");
    try {
      final Path logPath = Path.of("logs/latest.log");
      if (Files.exists(logPath)) {
        final List<String> logs = Files.readAllLines(logPath);
        for (final String log : logs) {
          dump.append(log).append("\n");
        }
      } else {
        dump.append("No logs found.\n");
      }
    } catch (final IOException e) {
      final String message = e.getMessage();
      dump.append("Error reading logs: ").append(message).append("\n");
    }

    return dump.toString();
  }
}
