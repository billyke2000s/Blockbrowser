package com.example.blockbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;

import org.junit.Test;

public class HlsPlaylistTest {
  private static final String BASE = "https://cdn.example.com/videos/abc/master.m3u8?token=xyz";

  private static final String MASTER =
      "#EXTM3U\n"
      + "#EXT-X-VERSION:6\n"
      + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio/en.m3u8\"\n"
      + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"French\",LANGUAGE=\"fr\",DEFAULT=NO,URI=\"audio/fr.m3u8\"\n"
      + "#EXT-X-STREAM-INF:BANDWIDTH=800000,AVERAGE-BANDWIDTH=700000,CODECS=\"avc1.4d401e,mp4a.40.2\",RESOLUTION=640x360,AUDIO=\"aud\"\n"
      + "360/index.m3u8\n"
      + "#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=90000,URI=\"iframes.m3u8\"\n"
      + "#EXT-X-STREAM-INF:BANDWIDTH=5200000,CODECS=\"avc1.640028,mp4a.40.2\",RESOLUTION=1920x1080,AUDIO=\"aud\"\n"
      + "https://other.example.net/1080/index.m3u8\n"
      + "#EXT-X-STREAM-INF:BANDWIDTH=4800000,RESOLUTION=1920x1080,AUDIO=\"aud\"\n"
      + "1080b/index.m3u8\n"
      + "#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS=\"mp4a.40.2\"\n"
      + "/audio-only/index.m3u8\n";

  @Test public void masterPlaylistListsQualitiesBestFirst() throws IOException {
    HlsPlaylist p = HlsPlaylist.parse(MASTER, BASE);
    assertTrue(p.isMaster);
    assertEquals(4, p.variants.size()); // the I-frame playlist is not a quality
    List<HlsPlaylist.Variant> q = p.qualities();
    assertEquals(3, q.size());           // two 1080p entries collapse into the higher bitrate one
    assertEquals(1080, q.get(0).height);
    assertEquals(5200000, q.get(0).bandwidth);
    assertEquals("https://other.example.net/1080/index.m3u8", q.get(0).uri);
    assertEquals("1080p · 5.2 Mbps", q.get(0).label());
    assertEquals(360, q.get(1).height);
    assertEquals("https://cdn.example.com/videos/abc/360/index.m3u8", q.get(1).uri);
    assertTrue(q.get(2).isAudioOnly());
    assertEquals("Audio only · 128 kbps", q.get(2).label());
    assertEquals("https://cdn.example.com/audio-only/index.m3u8", q.get(2).uri);
  }

  @Test public void separateAudioTrackPrefersDefault() throws IOException {
    HlsPlaylist p = HlsPlaylist.parse(MASTER, BASE);
    HlsPlaylist.Rendition audio = p.audioFor(p.qualities().get(0));
    assertNotNull(audio);
    assertEquals("en", audio.language);
    assertEquals("https://cdn.example.com/videos/abc/audio/en.m3u8", audio.uri);
    assertNull(p.audioFor(p.qualities().get(2)));
  }

  @Test public void audioInsideVideoNeedsNoSeparateTrack() throws IOException {
    String master = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"main\",DEFAULT=YES\n"
        + "#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720,AUDIO=\"a\"\nv.m3u8\n";
    HlsPlaylist p = HlsPlaylist.parse(master, BASE);
    assertNull(p.audioFor(p.variants.get(0))); // rendition without URI: audio is muxed into the video
  }

  @Test public void mediaPlaylistSegments() throws IOException {
    String media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-PLAYLIST-TYPE:VOD\n"
        + "#EXTINF:6.006,\nseg0.ts\n"
        + "#EXTINF:6.006,title here\n#EXT-X-DISCONTINUITY\nseg1.ts?sig=a%2Fb\n"
        + "#EXTINF:3.5,\nhttps://cdn2.example.com/seg2.ts\n"
        + "#EXT-X-ENDLIST\n";
    HlsPlaylist p = HlsPlaylist.parse(media, "https://cdn.example.com/v/720/index.m3u8");
    assertFalse(p.isMaster);
    assertTrue(p.ended);
    assertNull(p.encryption);
    assertNull(p.init);
    assertEquals(3, p.segments.size());
    assertEquals("https://cdn.example.com/v/720/seg0.ts", p.segments.get(0).uri);
    assertEquals("https://cdn.example.com/v/720/seg1.ts?sig=a%2Fb", p.segments.get(1).uri);
    assertEquals("https://cdn2.example.com/seg2.ts", p.segments.get(2).uri);
    assertEquals(15.512, p.duration(), 0.001);
  }

  @Test public void liveStreamHasNoEnd() throws IOException {
    HlsPlaylist p = HlsPlaylist.parse("#EXTM3U\n#EXTINF:2,\na.ts\n#EXTINF:2,\nb.ts\n", BASE);
    assertFalse(p.ended);
  }

  @Test public void encryptionIsDetected() throws IOException {
    HlsPlaylist aes = HlsPlaylist.parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x1\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n", BASE);
    assertEquals("AES-128", aes.encryption);
    HlsPlaylist none = HlsPlaylist.parse("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n", BASE);
    assertNull(none.encryption);
    HlsPlaylist drm = HlsPlaylist.parse("#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n"
        + "#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n", BASE);
    assertEquals("SAMPLE-AES", drm.encryption);
  }

  @Test public void fragmentedMp4WithByteRanges() throws IOException {
    String media = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-MAP:URI=\"video.mp4\",BYTERANGE=\"720@0\"\n"
        + "#EXTINF:4,\n#EXT-X-BYTERANGE:1000@720\nvideo.mp4\n"
        + "#EXTINF:4,\n#EXT-X-BYTERANGE:1500\nvideo.mp4\n"
        + "#EXTINF:2,\n#EXT-X-BYTERANGE:800\nvideo.mp4\n#EXT-X-ENDLIST\n";
    HlsPlaylist p = HlsPlaylist.parse(media, "https://x.example/a/b.m3u8");
    assertNotNull(p.init);
    assertEquals("https://x.example/a/video.mp4", p.init.uri);
    assertEquals(0, p.init.offset);
    assertEquals(720, p.init.length);
    assertEquals(720, p.segments.get(0).offset);
    assertEquals(1720, p.segments.get(1).offset);   // continues where the previous range ended
    assertEquals(1500, p.segments.get(1).length);
    assertEquals(3220, p.segments.get(2).offset);
  }

  @Test public void notAPlaylist() {
    for (String s : new String[]{"<html>Access denied</html>", "", "   \n "}) {
      try { HlsPlaylist.parse(s, BASE); fail("accepted: " + s); } catch (IOException expected) {}
    }
  }

  @Test public void byteOrderMarkAndWindowsLineEndings() throws IOException {
    HlsPlaylist p = HlsPlaylist.parse("﻿#EXTM3U\r\n#EXTINF:1,\r\na.ts\r\n#EXT-X-ENDLIST\r\n", BASE);
    assertEquals(1, p.segments.size());
    assertTrue(p.ended);
  }

  @Test public void attributeListsWithQuotedCommas() {
    java.util.Map<String, String> a = HlsPlaylist.attributes("BANDWIDTH=1,CODECS=\"avc1.4d401f,mp4a.40.2\",RESOLUTION=640x360");
    assertEquals("avc1.4d401f,mp4a.40.2", a.get("CODECS"));
    assertEquals("640x360", a.get("RESOLUTION"));
    assertEquals("1", a.get("BANDWIDTH"));
  }

  @Test public void addressesWithCharactersJavaRejects() {
    assertEquals("https://a.example/x/seg%201.ts", HlsPlaylist.resolve("https://a.example/x/p.m3u8", "seg 1.ts"));
    assertEquals("https://a.example/x/s.ts?a=1%7C2", HlsPlaylist.resolve("https://a.example/x/p.m3u8?k=a|b", "s.ts?a=1|2"));
  }
}
