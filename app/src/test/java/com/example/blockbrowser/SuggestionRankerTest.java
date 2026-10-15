package com.example.blockbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SuggestionRankerTest {
  private static final String SEARCH = "https://www.google.com/search?q=";
  private static final long NOW = 1_800_000_000_000L;

  private static List<SuggestionRanker.Suggestion> rank(String q, List<SuggestionRanker.Page> pages, List<String> remote) {
    return SuggestionRanker.rank(q, UrlTools.toNavigableUrl(q, SEARCH), pages, remote, SEARCH, NOW, 8);
  }

  private final List<SuggestionRanker.Page> pages = Arrays.asList(
      new SuggestionRanker.Page("https://github.com/", "GitHub", false, 30, NOW - 3_600_000),
      new SuggestionRanker.Page("https://news.ycombinator.com/", "Hacker News", true, 0, 0),
      new SuggestionRanker.Page("https://example.com/github-tips", "Tips for GitHub", false, 1, NOW - 86_400_000L * 30),
      new SuggestionRanker.Page("https://github.com/", "My GitHub", true, 0, 0));

  @Test public void addressPrefixBeatsTitleMatchAndSearch() {
    List<SuggestionRanker.Suggestion> r = rank("git", pages, Arrays.asList("git commands", "GIT"));
    assertEquals("https://github.com/", r.get(0).url);
    assertEquals(SuggestionRanker.BOOKMARK, r.get(0).type);          // merged with the bookmark
    assertEquals("My GitHub", r.get(0).title);                       // the bookmark's own name wins
    assertEquals(SuggestionRanker.SEARCH, r.get(1).type);
    assertEquals("https://example.com/github-tips", r.get(2).url);
    assertEquals(SuggestionRanker.SEARCH_SUGGESTION, r.get(3).type);
    assertEquals(4, r.size());                                        // "GIT" duplicates the typed text
  }

  @Test public void typedAddressComesFirst() {
    List<SuggestionRanker.Suggestion> r = rank("example.org", pages, null);
    assertEquals(SuggestionRanker.TYPED_URL, r.get(0).type);
    assertEquals("https://example.org", r.get(0).url);
  }

  @Test public void exactSavedAddressReplacesTypedRow() {
    List<SuggestionRanker.Suggestion> r = rank("github.com", pages, null);
    assertEquals(1, r.size());
    assertEquals(SuggestionRanker.BOOKMARK, r.get(0).type);
  }

  @Test public void titleMatchListedAfterSearch() {
    // Only address matches go above "search for…"; a title match comes right after it.
    List<SuggestionRanker.Suggestion> r = rank("hacker", pages, null);
    assertEquals(SuggestionRanker.SEARCH, r.get(0).type);
    assertEquals("https://news.ycombinator.com/", r.get(1).url);
  }

  @Test public void nothingForEmptyQueryAndLimitRespected() {
    assertTrue(rank("  ", pages, null).isEmpty());
    List<String> many = Arrays.asList("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10");
    assertEquals(8, rank("a", Collections.<SuggestionRanker.Page>emptyList(), many).size());
  }

  @Test public void nonWebPagesIgnored() {
    List<SuggestionRanker.Page> bad = Collections.singletonList(new SuggestionRanker.Page("javascript:alert(1)", "javascript", true, 5, NOW));
    for (SuggestionRanker.Suggestion s : rank("javascript", bad, null)) assertTrue(s.url.startsWith(SEARCH));
  }
}
