package com.example.blockbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UrlToolsTest {
  private static final String SEARCH = "https://duckduckgo.com/?q=";

  @Test public void addressesBecomeWebUrls() {
    assertEquals("https://example.com", UrlTools.toNavigableUrl("example.com", SEARCH));
    assertEquals("https://example.com/path?a=1", UrlTools.toNavigableUrl("  example.com/path?a=1 ", SEARCH));
    assertEquals("https://example.com:8443/a", UrlTools.toNavigableUrl("example.com:8443/a", SEARCH));
    assertEquals("http://localhost:8080/x", UrlTools.toNavigableUrl("localhost:8080/x", SEARCH));
    assertEquals("http://192.168.1.1", UrlTools.toNavigableUrl("192.168.1.1", SEARCH));
    assertEquals("http://example.com", UrlTools.toNavigableUrl("http://example.com", SEARCH));
    assertEquals("about:blank", UrlTools.toNavigableUrl("about:blank", SEARCH));
  }

  @Test public void textBecomesSearch() {
    assertEquals(SEARCH + "hello+world", UrlTools.toNavigableUrl("hello world", SEARCH));
    assertEquals(SEARCH + "weather", UrlTools.toNavigableUrl("weather", SEARCH));
    assertEquals(SEARCH + "example.com+login", UrlTools.toNavigableUrl("example.com login", SEARCH));
    assertNull(UrlTools.toNavigableUrl("   ", SEARCH));
  }

  @Test public void unsafeSchemesAreSearchedNotOpened() {
    for (String s : new String[]{"javascript:alert(1)", "file:///sdcard/a.txt", "content://x/y", "intent://x#Intent;end", "data:text/html,hi"}) {
      String result = UrlTools.toNavigableUrl(s, SEARCH);
      assertTrue(s + " -> " + result, result.startsWith(SEARCH));
    }
  }

  @Test public void hostAndOrigin() {
    assertEquals("www.example.com", UrlTools.host("https://user:pw@Www.Example.com:8080/p?q#f"));
    assertEquals("", UrlTools.host("not a url"));
    assertEquals("https://example.com:8080", UrlTools.origin("https://Example.com:8080/p?q"));
    assertEquals("", UrlTools.origin("ftp://example.com"));
    assertTrue(UrlTools.isIpv4("10.0.0.255"));
    assertFalse(UrlTools.isIpv4("10.0.0.256"));
  }

  @Test public void trackingParametersAreRemoved() {
    assertEquals("https://a.com/p?id=5#top", UrlTools.stripTrackingParams("https://a.com/p?utm_source=x&id=5&fbclid=y#top"));
    assertEquals("https://a.com/p", UrlTools.stripTrackingParams("https://a.com/p?utm_source=x&GCLID=1"));
    String untouched = "https://a.com/p?id=1&utm=keep";
    assertEquals(untouched, UrlTools.stripTrackingParams(untouched));
    assertEquals("mailto:a@b.com?utm_source=x", UrlTools.stripTrackingParams("mailto:a@b.com?utm_source=x"));
  }

  @Test public void display() {
    assertEquals("example.com", UrlTools.display("https://www.example.com/"));
    assertEquals("example.com/a?b", UrlTools.display("http://example.com/a?b"));
  }
}
