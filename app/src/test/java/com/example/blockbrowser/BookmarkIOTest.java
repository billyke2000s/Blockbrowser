package com.example.blockbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class BookmarkIOTest {
  private static final String CHROME_EXPORT = "<!DOCTYPE NETSCAPE-Bookmark-file-1>\n"
      + "<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n"
      + "<DL><p>\n"
      + "    <DT><H3 ADD_DATE=\"1\" PERSONAL_TOOLBAR_FOLDER=\"true\">Bookmarks bar</H3>\n"
      + "    <DL><p>\n"
      + "        <DT><A HREF=\"https://example.com/?a=1&amp;b=2\" ADD_DATE=\"1\" ICON=\"data:image/png;base64,AAA\">Example &amp; Co</A>\n"
      + "        <DT><H3>Work / Stuff</H3>\n"
      + "        <DL><p>\n"
      + "            <DT><A HREF='https://work.example.org/'>Work</A>\n"
      + "            <DT><A HREF=\"javascript:alert(1)\">Bookmarklet</A>\n"
      + "        </DL><p>\n"
      + "    </DL><p>\n"
      + "    <DT><A HREF=\"http://top.example.net\">Top &#8211; level</A>\n"
      + "    <DT><A HREF=\"file:///etc/passwd\">Local</A>\n"
      + "</DL><p>\n";

  @Test public void parsesNetscapeHtmlWithFolders() {
    BookmarkIO.Parsed p = BookmarkIO.parse(CHROME_EXPORT);
    assertEquals(3, p.bookmarks.size());
    assertEquals(2, p.rejected); // javascript: and file: entries
    BookmarkIO.Bookmark first = p.bookmarks.get(0);
    assertEquals("https://example.com/?a=1&b=2", first.url);
    assertEquals("Example & Co", first.title);
    assertEquals("Bookmarks bar", first.folder);
    assertEquals("Bookmarks bar/Work - Stuff", p.bookmarks.get(1).folder); // "/" inside a name can't create a level
    assertEquals("", p.bookmarks.get(2).folder);
    assertEquals("Top – level", p.bookmarks.get(2).title);
  }

  @Test public void htmlExportRoundTrips() {
    List<BookmarkIO.Bookmark> in = Arrays.asList(
        new BookmarkIO.Bookmark("https://a.example/?x=1&y=<2>", "A \"quoted\" <name>", ""),
        new BookmarkIO.Bookmark("https://b.example/", "B", "Folder/Sub"),
        new BookmarkIO.Bookmark("https://c.example/", "C", "Folder"));
    BookmarkIO.Parsed back = BookmarkIO.parse(BookmarkIO.toNetscapeHtml(in));
    assertEquals(3, back.bookmarks.size());
    for (BookmarkIO.Bookmark original : in) {
      boolean found = false;
      for (BookmarkIO.Bookmark b : back.bookmarks)
        if (b.url.equals(original.url) && b.title.equals(original.title) && b.folder.equals(original.folder)) found = true;
      assertTrue("lost " + original.url, found);
    }
  }

  @Test public void parsesOldAndNewJson() {
    BookmarkIO.Parsed old = BookmarkIO.parse("[{\"url\":\"https://a.example\",\"title\":\"A\"},{\"url\":\"ftp://x\"},{\"nope\":1},\"text\"]");
    assertEquals(1, old.bookmarks.size());
    assertEquals(3, old.rejected);
    BookmarkIO.Parsed wrapped = BookmarkIO.parse("{\"bookmarks\":[{\"url\":\"https://b.example\",\"title\":\"B\",\"folder\":\" x // y \"}]}");
    assertEquals("x/y", wrapped.bookmarks.get(0).folder);
    BookmarkIO.Parsed roundTrip = BookmarkIO.parse(BookmarkIO.toJson(wrapped.bookmarks));
    assertEquals("x/y", roundTrip.bookmarks.get(0).folder);
  }

  @Test public void invalidInputGivesNothing() {
    assertEquals(0, BookmarkIO.parse("[not json").bookmarks.size());
    assertEquals(0, BookmarkIO.parse("hello").bookmarks.size());
    assertEquals(0, BookmarkIO.parse("").bookmarks.size());
    assertEquals(0, BookmarkIO.parse(null).bookmarks.size());
  }

  @Test public void mergeSkipsDuplicates() {
    List<BookmarkIO.Bookmark> existing = Arrays.asList(new BookmarkIO.Bookmark("https://a.example/", "A", ""));
    List<BookmarkIO.Bookmark> incoming = Arrays.asList(
        new BookmarkIO.Bookmark("https://a.example/", "A again", "X"),
        new BookmarkIO.Bookmark("https://b.example/", "B", ""),
        new BookmarkIO.Bookmark("https://b.example/", "B twice", ""));
    int[] added = new int[1];
    List<BookmarkIO.Bookmark> merged = BookmarkIO.merge(existing, incoming, added);
    assertEquals(1, added[0]);
    assertEquals(2, merged.size());
    assertEquals("A", merged.get(0).title);
  }

  @Test public void longTitlesAndHugeUrlsAreLimited() {
    StringBuilder longTitle = new StringBuilder();
    for (int i = 0; i < 1000; i++) longTitle.append('t');
    StringBuilder hugeUrl = new StringBuilder("https://a.example/");
    for (int i = 0; i < 5000; i++) hugeUrl.append('x');
    BookmarkIO.Parsed p = BookmarkIO.parse("[{\"url\":\"https://a.example/\",\"title\":\"" + longTitle + "\"},{\"url\":\"" + hugeUrl + "\"}]");
    assertEquals(1, p.bookmarks.size());
    assertEquals(BookmarkIO.MAX_TITLE, p.bookmarks.get(0).title.length());
    assertEquals(1, p.rejected);
  }
}
