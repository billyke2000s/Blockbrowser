package com.example.blockbrowser;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a downloaded stream (joined MPEG-TS or fragmented-MP4 pieces, plus an optional separate audio track)
 * into one ordinary MP4 that any phone app can play, using Android's own MediaExtractor and MediaMuxer.
 * Nothing is re-encoded, so it is fast and keeps the original quality.
 */
final class StreamRemuxer {
  private StreamRemuxer() {}

  private static final int BUFFER_BYTES = 16 * 1024 * 1024;
  /** A timestamp this far behind the previous one means the stream restarted its clock (e.g. an inserted clip). */
  private static final long JUMP_BACK_US = 1_000_000;

  private static final class Track {
    int source, muxIndex;
    long offsetUs, lastUs = Long.MIN_VALUE, frameUs = 40_000;
  }

  /** Writes video (with its own audio, if any) and, when given, a separate audio file into one MP4. */
  static void toMp4(File video, File audio, File out) throws IOException {
    List<MediaExtractor> extractors = new ArrayList<>();
    MediaMuxer muxer = null;
    boolean started = false;
    try {
      MediaExtractor v = new MediaExtractor();
      extractors.add(v);
      v.setDataSource(video.getPath());
      MediaExtractor a = null;
      if (audio != null) {
        a = new MediaExtractor();
        extractors.add(a);
        a.setDataSource(audio.getPath());
      }
      muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

      // Pick the first video track, and the first audio track (from the separate file when there is one).
      List<List<Track>> perSource = new ArrayList<>();
      perSource.add(new ArrayList<>());
      perSource.add(new ArrayList<>());
      int[][] chosen = new int[2][];
      int videoTrack = firstTrack(v, "video/");
      if (videoTrack < 0) throw new IOException("no video track found");
      int audioInVideo = firstTrack(v, "audio/");
      int separateAudio = a == null ? -1 : firstTrack(a, "audio/");
      chosen[0] = audioInVideo >= 0 && separateAudio < 0 ? new int[]{videoTrack, audioInVideo} : new int[]{videoTrack};
      chosen[1] = separateAudio >= 0 ? new int[]{separateAudio} : new int[0];

      Track[][] tracks = new Track[2][];
      for (int s = 0; s < 2; s++) {
        MediaExtractor ex = s == 0 ? v : a;
        tracks[s] = new Track[ex == null ? 0 : ex.getTrackCount()];
        if (ex == null) continue;
        for (int index : chosen[s]) {
          MediaFormat f = ex.getTrackFormat(index);
          Track t = new Track();
          t.source = s;
          try {
            t.muxIndex = muxer.addTrack(f);
          } catch (Exception e) {
            if (index == videoTrack && s == 0) throw new IOException("this video format can't be saved as MP4", e);
            continue; // an audio format MP4 can't hold: keep the video rather than failing
          }
          if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            try { int fps = f.getInteger(MediaFormat.KEY_FRAME_RATE); if (fps > 0) t.frameUs = 1_000_000L / fps; } catch (Exception ignored) {}
          }
          ex.selectTrack(index);
          tracks[s][index] = t;
          perSource.get(s).add(t);
        }
      }
      // Every input starts its clock at an arbitrary value (MPEG-TS often at ~10 s); start the MP4 at zero.
      for (int s = 0; s < 2; s++) {
        MediaExtractor ex = s == 0 ? v : a;
        if (ex == null || perSource.get(s).isEmpty()) continue;
        long base = earliestTime(s == 0 ? video : audio, chosen[s]);
        for (Track t : perSource.get(s)) t.offsetUs = -base;
      }
      muxer.start();
      started = true;

      ByteBuffer buf = ByteBuffer.allocateDirect(BUFFER_BYTES);
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      for (int s = 0; s < 2; s++) {
        MediaExtractor ex = s == 0 ? v : a;
        if (ex == null || perSource.get(s).isEmpty()) continue;
        while (true) {
          int index = ex.getSampleTrackIndex();
          if (index < 0) break;
          Track t = index < tracks[s].length ? tracks[s][index] : null;
          if (t == null) { ex.advance(); continue; }
          buf.clear();
          int size = ex.readSampleData(buf, 0);
          if (size < 0) break;
          long time = ex.getSampleTime() + t.offsetUs;
          // Keep each track's clock moving forward across restarts, which MP4 requires.
          if (t.lastUs != Long.MIN_VALUE && time < t.lastUs - JUMP_BACK_US) {
            long shift = t.lastUs + t.frameUs - time;
            t.offsetUs += shift;
            time += shift;
          }
          if (time < 0) time = 0;
          t.lastUs = Math.max(t.lastUs, time);
          info.set(0, size, time, (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
          muxer.writeSampleData(t.muxIndex, buf, info);
          ex.advance();
        }
      }
      muxer.stop();
      started = false;
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("couldn't convert the video: " + e.getMessage(), e);
    } finally {
      if (muxer != null) {
        if (started) { try { muxer.stop(); } catch (Exception ignored) {} }
        try { muxer.release(); } catch (Exception ignored) {}
      }
      for (MediaExtractor ex : extractors) { try { ex.release(); } catch (Exception ignored) {} }
    }
  }

  static int firstTrack(MediaExtractor ex, String prefix) {
    for (int i = 0; i < ex.getTrackCount(); i++) {
      String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
      if (mime != null && mime.startsWith(prefix)) return i;
    }
    return -1;
  }

  /** Earliest timestamp among the first samples of the given tracks (video frames arrive out of order). */
  private static long earliestTime(File file, int[] trackIndexes) throws IOException {
    long min = Long.MAX_VALUE;
    for (int index : trackIndexes) {
      MediaExtractor ex = new MediaExtractor();
      try {
        ex.setDataSource(file.getPath());
        ex.selectTrack(index);
        for (int i = 0; i < 60; i++) {
          long t = ex.getSampleTime();
          if (t < 0) break;
          min = Math.min(min, t);
          if (!ex.advance()) break;
        }
      } finally {
        ex.release();
      }
    }
    return min == Long.MAX_VALUE ? 0 : min;
  }
}
