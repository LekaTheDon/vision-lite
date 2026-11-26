VOD auto-probe (2026-11-26): wired, not compiled yet.
- Done: HttpRangeReader (5 s timeouts), VodProbe (6 at once, 20 s cap, per-run cache 200), Link.probe/group,
  VodLinks.label from the file (picture, DV/HDR, Atmos, audio tracks, subs, tag subs, "in: group"), Detail live labels.
- Later: learn per playlist tag/group as fallback when a file can't be read.
