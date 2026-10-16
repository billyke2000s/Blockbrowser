#!/usr/bin/env bash
# Makes small HLS test streams with ffmpeg, for scripts/stream-download-check.sh (and local testing).
# Usage: make-test-streams.sh OUTPUT_DIR
#
#   ts/master.m3u8      two qualities (360p, 240p), audio inside the video pieces (MPEG-TS)
#   fmp4/master.m3u8    fragmented MP4, video and audio in separate playlists (like X/Twitter)
#   single/index.m3u8   one file addressed by byte ranges
#   enc/index.m3u8      AES-128 encrypted (must be refused)
#   live/index.m3u8     no end marker, i.e. a live stream (must be refused)
#   clip.mp4            a plain video file
# Every video is 12 seconds with a 440 Hz tone, so the test can check duration and that audio is present.
set -euo pipefail
OUT="${1:?output dir}"
rm -rf "$OUT"; mkdir -p "$OUT"
cd "$OUT"
SRC=(-f lavfi -i "testsrc2=size=640x360:rate=25:duration=12" -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=12")
ENC=(-c:v libx264 -preset veryfast -profile:v main -pix_fmt yuv420p -g 50 -c:a aac -b:a 96k -ac 2)
Q=(-hide_banner -loglevel error -y)

ffmpeg "${Q[@]}" "${SRC[@]}" "${ENC[@]}" -movflags +faststart clip.mp4

mkdir -p ts
ffmpeg "${Q[@]}" "${SRC[@]}" -filter_complex "[0:v]split=2[a][b];[b]scale=426:240[b2]" \
  -map "[a]" -map 1:a -map "[b2]" -map 1:a "${ENC[@]}" -b:v:0 800k -b:v:1 300k \
  -f hls -hls_time 3 -hls_playlist_type vod -hls_segment_type mpegts \
  -master_pl_name master.m3u8 -var_stream_map "v:0,a:0 v:1,a:1" -hls_segment_filename "ts/v%v_%03d.ts" "ts/v%v.m3u8"

mkdir -p fmp4
ffmpeg "${Q[@]}" "${SRC[@]}" -map 0:v -map 1:a "${ENC[@]}" -b:v 600k \
  -f hls -hls_time 3 -hls_playlist_type vod -hls_segment_type fmp4 \
  -master_pl_name master.m3u8 -var_stream_map "v:0,agroup:aud a:0,agroup:aud,default:yes" \
  -hls_fmp4_init_filename "init_%v.mp4" -hls_segment_filename "fmp4/s%v_%03d.m4s" "fmp4/p%v.m3u8"

mkdir -p single
ffmpeg "${Q[@]}" "${SRC[@]}" "${ENC[@]}" -f hls -hls_time 3 -hls_playlist_type vod -hls_flags single_file single/index.m3u8

mkdir -p enc
head -c 16 /dev/urandom > enc/key.bin
printf 'key.bin\nenc/key.bin\n' > enc/keyinfo
ffmpeg "${Q[@]}" "${SRC[@]}" "${ENC[@]}" -f hls -hls_time 3 -hls_playlist_type vod -hls_key_info_file enc/keyinfo \
  -hls_segment_filename "enc/e%03d.ts" enc/index.m3u8

mkdir -p live
cp ts/v1_00*.ts live/ 2>/dev/null || true
grep -v '#EXT-X-ENDLIST\|#EXT-X-PLAYLIST-TYPE' ts/v1.m3u8 | sed 's#v1_#v1_#' > live/index.m3u8

echo "Test streams written to $OUT:"
find . -name '*.m3u8' | sort
