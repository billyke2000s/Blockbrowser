package com.example.blockbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SearchSuggestionsTest {
  @Test public void openSearchFormat() {
    assertEquals(Arrays.asList("weather today", "weather tomorrow"),
        SearchSuggestions.parse("[\"weather\",[\"weather today\",\"weather tomorrow\"],[],{}]"));
  }

  @Test public void duckDuckGoObjectFormat() {
    assertEquals(Arrays.asList("cats", "cat videos"), SearchSuggestions.parse("[{\"phrase\":\"cats\"},{\"phrase\":\"cat videos\"}]"));
  }

  @Test public void qwantFormat() {
    assertEquals(Arrays.asList("paris"), SearchSuggestions.parse("{\"status\":\"success\",\"data\":{\"items\":[{\"value\":\"paris\"}]}}"));
  }

  @Test public void junkGivesNothingAndListIsCapped() {
    assertTrue(SearchSuggestions.parse("<html>").isEmpty());
    assertTrue(SearchSuggestions.parse("[1,2").isEmpty());
    assertTrue(SearchSuggestions.parse(null).isEmpty());
    List<String> capped = SearchSuggestions.parse("[\"a\",[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\"]]");
    assertEquals(8, capped.size());
  }

  @Test public void everyEngineHasAnEntry() {
    assertEquals(MainActivity.ENGINE_NAMES.length, SearchSuggestions.ENDPOINTS.length);
  }
}
