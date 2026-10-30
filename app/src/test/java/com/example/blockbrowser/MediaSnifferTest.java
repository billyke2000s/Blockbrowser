package com.example.blockbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class MediaSnifferTest {
  @Test public void classifiesVideoAddresses() {
    assertEquals(MediaSniffer.Kind.HLS, MediaSniffer.classify("https://v.example.com/a/master.m3u8?token=1"));
    assertEquals(MediaSniffer.Kind.HLS, MediaSniffer.classify("https://v.example.com/HLS/Index.M3U8"));
    assertEquals(MediaSniffer.Kind.DASH, MediaSniffer.classify("https://v.example.com/manifest.mpd"));
    assertEquals(MediaSniffer.Kind.FILE, MediaSniffer.classify("https://v.example.com/clip.mp4?x=1#t=5"));
    assertEquals(MediaSniffer.Kind.FILE, MediaSniffer.classify("http://localhost:8765/a.webm"));
  }

  @Test public void preservesFileContainerMime() {
    assertEquals("video/mp4", MediaSniffer.fileMime("https://v.example/clip.M4V?key=1"));
    assertEquals("video/webm", MediaSniffer.fileMime("https://v.example/clip.webm"));
    assertEquals("video/x-matroska", MediaSniffer.fileMime("https://v.example/clip.mkv"));
    assertEquals("video/quicktime", MediaSniffer.fileMime("https://v.example/clip.mov"));
    assertEquals("video/ogg", MediaSniffer.fileMime("https://v.example/clip.ogv"));
    assertNull(MediaSniffer.fileMime("https://v.example/playlist.m3u8"));
  }

  @Test public void ignoresPiecesAndEverythingElse() {
    assertNull(MediaSniffer.classify("https://v.example.com/seg-001.ts"));
    assertNull(MediaSniffer.classify("https://v.example.com/chunk.m4s"));
    assertNull(MediaSniffer.classify("https://v.example.com/v.mp4?bytestart=0&byteend=9999"));
    assertNull(MediaSniffer.classify("https://v.example.com/page.html?file=a.mp4"));
    assertNull(MediaSniffer.classify("blob:https://v.example.com/1234"));
    assertNull(MediaSniffer.classify("data:video/mp4;base64,AAAA"));
    assertNull(MediaSniffer.classify(null));
  }

  @Test public void logKeepsStreamsFirstNewestFirstWithoutDuplicates() {
    MediaSniffer.Log log = new MediaSniffer.Log();
    assertTrue(log.offer("https://a.example/one.mp4"));
    assertTrue(log.offer("https://a.example/a.m3u8"));
    assertFalse(log.offer("https://a.example/a.m3u8"));
    assertFalse(log.offer("https://a.example/seg.ts"));
    assertTrue(log.offer("https://a.example/two.mp4"));
    assertTrue(log.offer("https://a.example/b.m3u8"));
    List<MediaSniffer.Found> s = log.snapshot();
    assertEquals(4, s.size());
    assertEquals("https://a.example/b.m3u8", s.get(0).url);
    assertEquals("https://a.example/a.m3u8", s.get(1).url);
    assertEquals("https://a.example/two.mp4", s.get(2).url);
    assertEquals("https://a.example/one.mp4", s.get(3).url);
    log.clear();
    assertTrue(log.snapshot().isEmpty());
  }

  @Test public void logIsBounded() {
    MediaSniffer.Log log = new MediaSniffer.Log();
    for (int i = 0; i < 100; i++) log.offer("https://a.example/" + i + ".mp4");
    assertEquals(MediaSniffer.Log.MAX, log.snapshot().size());
    assertEquals("https://a.example/99.mp4", log.snapshot().get(0).url);
  }
}
