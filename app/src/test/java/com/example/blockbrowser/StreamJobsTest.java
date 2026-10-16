package com.example.blockbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.Test;

public class StreamJobsTest {
  @Test public void fileNamesAreSafe() {
    assertEquals("My Video_ part 1.mp4", StreamJobs.fileName("  My Video: part 1 ", "https://a.example/x"));
    assertEquals("video from a.example.mp4", StreamJobs.fileName("", "https://a.example/x"));
    assertEquals("video from a.example.mp4", StreamJobs.fileName("https://a.example/page", "https://a.example/x"));
    assertEquals("_.hidden.mp4", StreamJobs.fileName(".hidden", null));
    StringBuilder longTitle = new StringBuilder();
    for (int i = 0; i < 30; i++) longTitle.append("abcdefgh ");
    assertTrue(StreamJobs.fileName(longTitle.toString(), null).length() <= 104);
  }

  @Test public void jobsRoundTripAndPrivateOnesKeepNoAddresses() throws Exception {
    File dir = Files.createTempDirectory("jobs").toFile();
    StreamJobs.Job normal = new StreamJobs.Job();
    normal.id = 1; normal.created = 1; normal.title = "a.mp4"; normal.status = StreamJobs.DONE;
    normal.video = "https://v.example/a.m3u8"; normal.referer = "https://v.example/page"; normal.size = 1234;
    StreamJobs.save(dir, normal);
    StreamJobs.Job priv = new StreamJobs.Job();
    priv.id = 2; priv.created = 2; priv.title = "b.mp4"; priv.status = StreamJobs.FAILED; priv.isPrivate = true;
    priv.video = "https://secret.example/b.m3u8"; priv.referer = "https://secret.example/page";
    StreamJobs.save(dir, priv);

    List<StreamJobs.Job> jobs = StreamJobs.list(dir);
    assertEquals(2, jobs.size());
    assertEquals(2, jobs.get(0).id); // newest first
    assertTrue(jobs.get(0).isPrivate);
    assertNull(jobs.get(0).video);
    assertNull(jobs.get(0).referer);
    String onDisk = new String(Files.readAllBytes(new File(StreamJobs.dir(dir), "2.json").toPath()), "UTF-8");
    assertFalse(onDisk.contains("secret.example"));
    assertEquals("https://v.example/a.m3u8", jobs.get(1).video);
    assertEquals(1234, jobs.get(1).size);

    StreamJobs.delete(dir, 1);
    assertEquals(1, StreamJobs.list(dir).size());
  }

  @Test public void cutOffDownloadsShowAsInterrupted() throws Exception {
    File dir = Files.createTempDirectory("jobs").toFile();
    StreamJobs.Job j = new StreamJobs.Job();
    j.id = 5; j.created = 5; j.status = StreamJobs.RUNNING;
    StreamJobs.save(dir, j);
    assertEquals(StreamJobs.RUNNING, StreamJobs.list(dir).get(0).status); // fresh: still running
    StreamJobs.markInterrupted(dir);
    StreamJobs.Job after = StreamJobs.load(dir, 5);
    assertEquals(StreamJobs.FAILED, after.status);
    assertEquals("interrupted", after.error);
  }
}
