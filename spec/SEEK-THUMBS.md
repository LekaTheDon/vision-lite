# Seek thumbnails — own design (step 1 of "fully mine", 2026-11-28)

Replaces Arc's engine (com/nuvio/tv/core/player/thumbnail, 43 files / 10,482 lines, plus cpp thumb_* + ffmpeg headers).
Written from behaviour and from the file formats (Matroska/EBML, ISO-BMFF, HEVC/AVC config records), not from Arc's code.

## What the user sees (kept)
- While seeking: a picture above the bar at the seek point, with the time; once enough pictures exist, a strip of
  neighbours either side of a larger framed centre picture (today's ThumbPane look, Kit corners, Palette ring).
- Seek lands on the keyframe the picture showed, so picture and video match.
- Settings: Seek thumbnails on/off; "for" All / HD only.
- Never for live TV / catch-up, never while DV7 plays on the FEL path, ExoPlayer only.

## How (own)
1. Index: one 256 KB read at the start (EBML header, SeekHead, Info, Tracks), then one read of the Cues
   (MP4: moov stss/stts/stsc/stco/stsz). Prototype on a 10.5 GB 4K HEVC MKV: 1.5 s, 387 KB, 3 requests, 597 cue points.
2. Picture: one range read at cue cluster + relative position → the video keyframe only (90–530 KB).
3. Decode: hardware decoder (MediaCodec, ByteBuffer output), one frame, then released. Google's software HEVC decoder
   can't do 10-bit (error 0x43dd), so hardware first; AVC can use software. HDR: simple PQ→SDR lift.
4. Downscale while converting YUV→RGB straight to 320×180 (no 4K bitmap ever made).
5. Order: around the cursor first, then fill the rest evenly; pause while playback's buffer is low; stop on close.
6. Memory: pictures kept as small JPEG/ARGB in an LRU (~8 MB); disk cache per title, budget, trimmed off cold start.
Prototype numbers (AM9, TorBox): ~0.55 s fetch + ~0.25 s decode per picture vs 27–58 s with MediaMetadataRetriever.

## Formats (user 2026-11-28: support as many as possible)
- Through Android's own decoders (on every Android TV, hardware first, software fallback): HEVC (8/10-bit), AVC,
  AV1, VP9, VP8, MPEG-2, MPEG-4 Part 2 (XviD/DivX), H.263, MJPEG.
- VC-1 / WMV3 (no Android decoder): own small JNI over the libavcodec that mpv already ships (dlopen, nothing extra
  linked), written fresh; last step.
- Containers: MKV/WebM (Cues), MP4/MOV (stss), AVI (idx1), TS/M2TS (scan for keyframes by PCR/PTS).

## Dropped (bloat / rare)
- Arc's media-server thumbs, Usenet copy logic, next-episode prefetch, copy choice. Revisit only if missed.

## Not verified yet
- Hardware decoder while ExoPlayer plays 4K at the same time (test with the user's OK, not by driving the TV).
- DV profile 5 colours (IPT): expected wrong; skip pictures for DV5 until handled.
- AV1, MP4, AVI files.
Target size: ≤ 900 lines Kotlin, no native code.
