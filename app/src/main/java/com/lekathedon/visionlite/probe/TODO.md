VOD auto-probe (WIP 2026-11-26, not compiled):
- Done: Vision DvProfileProbe + readers copied (package probe); MKV/MP4 sideTracks (audio/subs) added.
- Left: own HttpRangeReader (OkHttp Range, Net.http with 5s timeouts) replacing HttpRangeReader/thumbnailHttpClient in DvProfileProbe;
  data class ProbeTrack(kind, codec, lang, channels, name, forced); probe returns video label + tracks; cache by url hash;
  Detail: Sheet.Menu live labels (probe on open, max 6 parallel); VodLinks.label: drop dangling "·", add audio lang,
  subs langs, Atmos from track name, tag (AR-SUBS → Arabic subs) + "in: <group>"; learn per playlist tag/group as fallback.
- Then build 0.41.2 (99): also carries skin-menu fix (73c… pushed) + player notes.
