package com.example.blockbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/** Runs the stream downloader against a small in-memory web server. */
public class HlsDownloadTest {

  /**
   * A tiny web server on a plain socket (the JDK's com.sun.net.httpserver isn't available to Android unit tests).
   * Serves fixed files; answers 503 to the first request for "flaky" files, redirects /moved.m3u8, honours Range.
   */
  static final class Server implements AutoCloseable {
    final ServerSocket socket;
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final Map<String, String> cookies = new ConcurrentHashMap<>();
    final AtomicInteger flakyFailures = new AtomicInteger();
    final String base;
    final ExecutorService pool = Executors.newCachedThreadPool();

    Server() throws IOException {
      socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
      base = "http://127.0.0.1:" + socket.getLocalPort();
      pool.execute(() -> {
        while (!socket.isClosed()) {
          try { Socket c = socket.accept(); pool.execute(() -> handle(c)); } catch (IOException e) { return; }
        }
      });
    }

    private void handle(Socket c) {
      try (Socket conn = c) {
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.ISO_8859_1));
        String requestLine = in.readLine();
        if (requestLine == null) return;
        String path = requestLine.split(" ")[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        String range = null, cookie = null, line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
          int colon = line.indexOf(':');
          if (colon < 0) continue;
          String name = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT), value = line.substring(colon + 1).trim();
          if (name.equals("range")) range = value;
          if (name.equals("cookie")) cookie = value;
        }
        cookies.put(path, String.valueOf(cookie));
        OutputStream out = conn.getOutputStream();
        if (path.equals("/moved.m3u8")) { respond(out, "302 Found", "Location: /sub/media.m3u8\r\n", new byte[0]); return; }
        if (path.contains("flaky") && flakyFailures.getAndIncrement() == 0) { respond(out, "503 Unavailable", "", new byte[0]); return; }
        byte[] body = files.get(path);
        if (body == null) { respond(out, "404 Not Found", "", new byte[0]); return; }
        if (range != null && range.startsWith("bytes=")) {
          String[] r = range.substring(6).split("-", -1);
          int from = Integer.parseInt(r[0]);
          int to = r[1].isEmpty() ? body.length - 1 : Integer.parseInt(r[1]);
          byte[] part = new byte[to - from + 1];
          System.arraycopy(body, from, part, 0, part.length);
          String header = path.equals("/bad-range.bin")
              ? "Content-Range: bytes " + from + "-" + (to + 1) + "/" + body.length + "\r\n"
              : "Content-Range: bytes " + from + "-" + to + "/" + body.length + "\r\n";
          respond(out, "206 Partial Content", header, part);
        } else {
          respond(out, "200 OK", "", body);
        }
      } catch (IOException ignored) {}
    }

    private static void respond(OutputStream out, String status, String headers, byte[] body) throws IOException {
      out.write(("HTTP/1.1 " + status + "\r\n" + headers + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
      out.write(body);
      out.flush();
    }

    void put(String path, String text) { files.put(path, text.getBytes(StandardCharsets.UTF_8)); }
    void put(String path, byte[] data) { files.put(path, data); }
    @Override public void close() throws IOException { socket.close(); pool.shutdownNow(); }
  }

  private static File tempDir() throws IOException { return Files.createTempDirectory("hls-test").toFile(); }

  private static HlsDownload.Http http(Server s) {
    return new HlsDownload.Http("TestAgent", s.base + "/page", url -> url.startsWith(s.base) ? "session=abc" : null);
  }

  @Test public void piecesAreJoinedInOrderWithRetryAndCookies() throws Exception {
    try (Server s = new Server()) {
      StringBuilder pl = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:2\n");
      StringBuilder expected = new StringBuilder();
      for (int i = 0; i < 25; i++) {
        String name = i == 7 ? "flaky7.ts" : "p" + i + ".ts";
        String data = "<piece " + i + ">";
        s.put("/v/" + name, data);
        pl.append("#EXTINF:2,\n").append(name).append('\n');
        expected.append(data);
      }
      pl.append("#EXT-X-ENDLIST\n");
      s.put("/v/media.m3u8", pl.toString());

      int[] last = new int[2];
      HlsDownload.Result r = HlsDownload.download(http(s), s.base + "/v/media.m3u8", null, tempDir(), new HlsDownload.Listener() {
        @Override public void progress(int done, int total, long bytes) { last[0] = done; last[1] = total; }
        @Override public boolean cancelled() { return false; }
      });
      assertEquals(expected.toString(), new String(Files.readAllBytes(r.video.toPath()), StandardCharsets.UTF_8));
      assertNull(r.audio);
      assertEquals(50.0, r.duration, 0.001);
      assertEquals(25, last[0]);
      assertEquals(25, last[1]);
      assertEquals(2, s.flakyFailures.get()); // requested twice: a 503, then success
      assertEquals("session=abc", s.cookies.get("/v/p3.ts"));
    }
  }

  @Test public void masterPlaylistTakesBestQualityAndSeparateAudio() throws Exception {
    try (Server s = new Server()) {
      s.put("/m/master.m3u8", "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",DEFAULT=YES,URI=\"audio.m3u8\"\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=100,RESOLUTION=320x180,AUDIO=\"a\"\nlow.m3u8\n"
          + "#EXT-X-STREAM-INF:BANDWIDTH=900,RESOLUTION=1280x720,AUDIO=\"a\"\nhigh.m3u8\n");
      s.put("/m/high.m3u8", "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:1,\nh1.m4s\n#EXTINF:1,\nh2.m4s\n#EXT-X-ENDLIST\n");
      s.put("/m/low.m3u8", "#EXTM3U\n#EXTINF:1,\nl1.ts\n#EXT-X-ENDLIST\n");
      s.put("/m/audio.m3u8", "#EXTM3U\n#EXTINF:1,\na1.aac\n#EXTINF:1,\na2.aac\n#EXT-X-ENDLIST\n");
      s.put("/m/init.mp4", "INIT");
      s.put("/m/h1.m4s", "H1");
      s.put("/m/h2.m4s", "H2");
      // Audio pieces start with an ID3 timestamp tag (10-byte header + 4 bytes), which is removed.
      s.put("/m/a1.aac", new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4, 'A', '1'});
      s.put("/m/a2.aac", "A2");
      HlsDownload.Result r = HlsDownload.download(http(s), s.base + "/m/master.m3u8", null, tempDir(), null);
      assertEquals("INITH1H2", new String(Files.readAllBytes(r.video.toPath()), StandardCharsets.UTF_8));
      assertTrue(r.videoFragmentedMp4);
      assertEquals("A1A2", new String(Files.readAllBytes(r.audio.toPath()), StandardCharsets.UTF_8));
    }
  }

  @Test public void byteRangesAndRedirects() throws Exception {
    try (Server s = new Server()) {
      s.put("/sub/media.m3u8", "#EXTM3U\n#EXTINF:1,\n#EXT-X-BYTERANGE:3@2\nall.bin\n#EXTINF:1,\n#EXT-X-BYTERANGE:4\nall.bin\n#EXT-X-ENDLIST\n");
      s.put("/sub/all.bin", "..abcdefg..");
      // The playlist is reached through a redirect, so its pieces must resolve against /sub/.
      HlsDownload.Result r = HlsDownload.download(http(s), s.base + "/moved.m3u8", null, tempDir(), null);
      assertEquals("abcdefg", new String(Files.readAllBytes(r.video.toPath()), StandardCharsets.UTF_8));
    }
  }

  @Test public void rejectsIncorrectPartialContentRange() throws Exception {
    try (Server s = new Server()) {
      s.put("/bad-range.bin", "0123456789");
      HlsDownload.Http http = http(s);
      try {
        http.get(s.base + "/bad-range.bin", 2, 3, 100);
        fail("incorrect Content-Range was accepted");
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("byte range"));
      }
    }
  }

  @Test public void refusesProtectedAndLiveStreams() throws Exception {
    try (Server s = new Server()) {
      s.put("/enc.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST\n");
      s.put("/live.m3u8", "#EXTM3U\n#EXTINF:1,\na.ts\n");
      for (String p : new String[]{"/enc.m3u8", "/live.m3u8"}) {
        try { HlsDownload.download(http(s), s.base + p, null, tempDir(), null); fail(p + " was downloaded"); }
        catch (HlsDownload.Refused expected) {}
      }
    }
  }

  @Test public void missingPieceFailsWithoutEndlessRetries() throws Exception {
    try (Server s = new Server()) {
      s.put("/m.m3u8", "#EXTM3U\n#EXTINF:1,\ngone.ts\n#EXT-X-ENDLIST\n");
      long start = System.currentTimeMillis();
      try { HlsDownload.download(http(s), s.base + "/m.m3u8", null, tempDir(), null); fail("no error"); }
      catch (HlsDownload.HttpError e) { assertEquals(404, e.code); }
      assertTrue("404 should not be retried", System.currentTimeMillis() - start < 3000);
    }
  }

  @Test public void cookiesOnlyGoWhereTheSourceAllows() throws Exception {
    try (Server s = new Server()) {
      s.put("/c.m3u8", "#EXTM3U\n#EXTINF:1,\nx.ts\n#EXT-X-ENDLIST\n");
      s.put("/x.ts", "X");
      HlsDownload.Http noCookies = new HlsDownload.Http("UA", null, url -> null);
      HlsDownload.download(noCookies, s.base + "/c.m3u8", null, tempDir(), null);
      assertEquals("null", s.cookies.get("/x.ts"));
    }
  }

  @Test public void readAllHonoursSkipAndLength() throws IOException {
    byte[] data = "0123456789".getBytes(StandardCharsets.UTF_8);
    assertEquals("345", new String(HlsDownload.readAll(new ByteArrayInputStream(data), 3, 3, 100), StandardCharsets.UTF_8));
    assertEquals("789", new String(HlsDownload.readAll(new ByteArrayInputStream(data), 7, -1, 100), StandardCharsets.UTF_8));
    try { HlsDownload.readAll(new ByteArrayInputStream(data), 8, 5, 100); fail("short read accepted"); } catch (IOException expected) {}
    try { HlsDownload.readAll(new ByteArrayInputStream(data), 0, -1, 4); fail("oversize accepted"); } catch (IOException expected) {}
  }

  @Test public void id3TagsAreStripped() {
    byte[] tagged = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 2, 9, 9, 'a', 'b'};
    assertEquals("ab", new String(HlsDownload.stripId3(tagged), StandardCharsets.UTF_8));
    byte[] plain = {0x47, 1, 2};
    assertTrue(plain == HlsDownload.stripId3(plain));
  }
}
