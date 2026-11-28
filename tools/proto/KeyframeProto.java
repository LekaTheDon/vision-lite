import java.io.*; import java.net.*; import java.nio.*; import java.util.*;
import android.media.*; import android.graphics.*;
// Prototype: own Matroska keyframe thumbnails. Cues -> one range read per picture -> software decoder -> small bitmap.
public class K {
  static String url; static long size, got; static int reqs;
  static byte[] read(long pos, int len) throws IOException {
    len = (int) Math.min(len, size - pos);
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setRequestProperty("Range", "bytes=" + pos + "-" + (pos + len - 1)); c.setConnectTimeout(8000); c.setReadTimeout(15000);
    InputStream in = c.getInputStream(); byte[] b = new byte[len]; int o = 0, n;
    while (o < len && (n = in.read(b, o, len - o)) > 0) o += n; in.close(); got += o; reqs++;
    return o == len ? b : Arrays.copyOf(b, o);
  }
  // EBML reading over a byte array
  static class R { byte[] b; int p; long base; R(byte[] b, long base) { this.b = b; this.base = base; }
    boolean has(int n) { return p + n <= b.length; }
    long id() { int f = b[p] & 0xff; int len = f >= 0x80 ? 1 : f >= 0x40 ? 2 : f >= 0x20 ? 3 : 4; long v = 0; for (int i = 0; i < len; i++) v = v << 8 | (b[p++] & 0xff); return v; }
    long size() { int f = b[p] & 0xff; int len = 1; while (len <= 8 && (f & (0x80 >> (len - 1))) == 0) len++;
      long v = f & (0xff >> len); boolean all = v == (0xff >> len); p++;
      for (int i = 1; i < len; i++) { int x = b[p++] & 0xff; v = v << 8 | x; if (x != 0xff) all = false; }
      return all ? -1 : v; }
    long uint(long n) { long v = 0; for (int i = 0; i < n; i++) v = v << 8 | (b[p++] & 0xff); return v; }
    double flt(long n) { long v = uint(n); return n == 4 ? Float.intBitsToFloat((int) v) : Double.longBitsToDouble(v); }
  }
  static long segData, scale = 1000000, cuesAt = -1, tracksAt = -1; static double durTc;
  static int vTrack = -1; static String codec; static byte[] priv; static int W, H;
  static List<long[]> cues = new ArrayList<>(); // timeMs, clusterPos(abs), relPos
  static void tracks(R r, long end) {
    while (r.p < end) { long id = r.id(), s = r.size(); long e = r.p + s;
      if (id == 0xAE) { int num = 0, type = 0; String cid = null; byte[] cp = null; int w = 0, h = 0;
        while (r.p < e) { long i2 = r.id(), s2 = r.size(); long e2 = r.p + s2;
          if (i2 == 0xD7) num = (int) r.uint(s2); else if (i2 == 0x83) type = (int) r.uint(s2);
          else if (i2 == 0x86) { cid = new String(r.b, r.p, (int) s2); r.p = (int) e2; }
          else if (i2 == 0x63A2) { cp = Arrays.copyOfRange(r.b, r.p, (int) e2); r.p = (int) e2; }
          else if (i2 == 0xE0) { while (r.p < e2) { long i3 = r.id(), s3 = r.size(); if (i3 == 0xB0) w = (int) r.uint(s3); else if (i3 == 0xBA) h = (int) r.uint(s3); else r.p += s3; } }
          else r.p = (int) e2; }
        if (type == 1 && vTrack < 0) { vTrack = num; codec = cid; priv = cp; W = w; H = h; }
      } r.p = (int) e; }
  }
  static void cues(R r, long end) {
    while (r.p < end) { long id = r.id(), s = r.size(); long e = r.p + s;
      if (id == 0xBB) { long t = 0, cl = -1, rel = -1, tr = -1;
        while (r.p < e) { long i2 = r.id(), s2 = r.size(); long e2 = r.p + s2;
          if (i2 == 0xB3) t = r.uint(s2);
          else if (i2 == 0xB7) { long tr0 = -1, cl0 = -1, rel0 = -1; while (r.p < e2) { long i3 = r.id(), s3 = r.size();
              if (i3 == 0xF7) tr0 = r.uint(s3); else if (i3 == 0xF1) cl0 = r.uint(s3); else if (i3 == 0xF0) rel0 = r.uint(s3); else r.p += s3; }
            if (tr0 == vTrack || tr < 0) { tr = tr0; cl = cl0; rel = rel0; } }
          else r.p = (int) e2; }
        if (tr == vTrack && cl >= 0) cues.add(new long[]{ t * scale / 1000000, segData + cl, rel });
      } r.p = (int) e; }
  }
  static void open() throws IOException {
    byte[] b = read(0, 256 * 1024); R r = new R(b, 0);
    long id = r.id(), s = r.size(); r.p += s;        // EBML header
    id = r.id(); s = r.size(); segData = r.p;         // Segment
    while (r.has(12)) { int at = r.p; id = r.id(); s = r.size(); long e = r.p + s;
      if (id == 0x1F43B675) break;                    // first Cluster
      if (id == 0x114D9B74) { while (r.p < e) { long i2 = r.id(), s2 = r.size(); long e2 = r.p + s2; long sid = 0, pos = -1;
          while (r.p < e2) { long i3 = r.id(), s3 = r.size(); if (i3 == 0x53AB) sid = r.uint(s3); else if (i3 == 0x53AC) pos = r.uint(s3); else r.p += s3; }
          if (sid == 0x1C53BB6B) cuesAt = segData + pos; if (sid == 0x1654AE6B) tracksAt = segData + pos; } }
      else if (id == 0x1549A966 && e <= b.length) { while (r.p < e) { long i2 = r.id(), s2 = r.size(); if (i2 == 0x2AD7B1) scale = r.uint(s2); else if (i2 == 0x4489) durTc = r.flt(s2); else r.p += s2; } }
      else if (id == 0x1654AE6B && e <= b.length) { tracks(r, e); tracksAt = -2; }
      r.p = (int) Math.min(e, b.length); if (e > b.length) break; }
    if (tracksAt >= 0) { byte[] t = read(tracksAt, 256 * 1024); R q = new R(t, tracksAt); q.id(); long ts = q.size(); tracks(q, q.p + ts); }
    if (cuesAt < 0) throw new IOException("no cues");
    byte[] h = read(cuesAt, 16); R q = new R(h, cuesAt); q.id(); long cs = q.size(); int hl = q.p;
    byte[] c = read(cuesAt + hl, (int) cs); cues(new R(c, 0), c.length);
  }
  // The video keyframe at a cue: one read at cluster + relative position (or scan the cluster's first blocks).
  static byte[] keyframe(long[] cue) throws IOException {
    long at = cue[1]; byte[] b = read(at, 512 * 1024); R r = new R(b, at);
    r.id(); r.size();
    long target = cue[2] >= 0 ? r.p + cue[2] : -1;
    while (true) {
      if (target >= 0) r.p = (int) target;
      if (!r.has(16)) return null;
      long id = r.id(), s = r.size(); long e = r.p + s;
      if (id == 0xA0) { target = -1; continue; }   // BlockGroup: step inside
      if (id == 0xA3 || id == 0xA1) {
        int st = r.p; long tr = r.size(); int hdr = r.p - st + 3; int flags = b[r.p + 2] & 0xff;
        if (tr == vTrack && ((flags & 0x80) != 0 || id == 0xA1) && (flags & 0x06) == 0) {
          int len = (int) (s - hdr); long dataAt = at + st + hdr;
          if (st + hdr + len <= b.length) return Arrays.copyOfRange(b, st + hdr, st + hdr + len);
          return read(dataAt, len);
        }
      }
      target = -1; r.p = (int) e;
      if (e >= b.length) return null;
    }
  }
  static ByteArrayOutputStream annexB(byte[] nal, ByteArrayOutputStream o) { o.write(0); o.write(0); o.write(0); o.write(1); o.write(nal, 0, nal.length); return o; }
  static int nalLen = 4; static boolean STRIP = System.getenv("STRIP") != null;
  static byte[][] csd() {
    ByteArrayOutputStream a = new ByteArrayOutputStream(), b2 = new ByteArrayOutputStream(); byte[] p = priv;
    if (codec.startsWith("V_MPEGH/ISO/HEVC")) { nalLen = (p[21] & 3) + 1; int n = p[22] & 0xff, i = 23;
      for (int k = 0; k < n; k++) { int cnt = (p[i + 1] & 0xff) << 8 | (p[i + 2] & 0xff); i += 3;
        for (int j = 0; j < cnt; j++) { int l = (p[i] & 0xff) << 8 | (p[i + 1] & 0xff); i += 2; annexB(Arrays.copyOfRange(p, i, i + l), a); i += l; } }
      return new byte[][]{ a.toByteArray() }; }
    nalLen = (p[4] & 3) + 1; int ns = p[5] & 0x1f, i = 6;
    for (int k = 0; k < ns; k++) { int l = (p[i] & 0xff) << 8 | (p[i + 1] & 0xff); i += 2; annexB(Arrays.copyOfRange(p, i, i + l), a); i += l; }
    int np = p[i++] & 0xff; for (int k = 0; k < np; k++) { int l = (p[i] & 0xff) << 8 | (p[i + 1] & 0xff); i += 2; annexB(Arrays.copyOfRange(p, i, i + l), b2); i += l; }
    return new byte[][]{ a.toByteArray(), b2.toByteArray() };
  }
  static byte[] frameAnnexB(byte[] f) { ByteArrayOutputStream o = new ByteArrayOutputStream(f.length + 64); int i = 0;
    while (i + nalLen <= f.length) { int l = 0; for (int k = 0; k < nalLen; k++) l = l << 8 | (f[i + k] & 0xff); i += nalLen;
      if (l <= 0 || i + l > f.length) break; int ty = (f[i] >> 1) & 0x3f; if (STRIP && (ty >= 48 || ty == 35)) { i += l; continue; } o.write(0); o.write(0); o.write(0); o.write(1); o.write(f, i, l); i += l; }
    return o.toByteArray(); }
  static Bitmap decode(String name, byte[][] csd, byte[] frame, int tw, int th) throws Exception {
    String mime = codec.contains("HEVC") ? "video/hevc" : "video/avc";
    MediaCodec mc = MediaCodec.createByCodecName(name);
    MediaFormat f = MediaFormat.createVideoFormat(mime, W, H);
    f.setByteBuffer("csd-0", ByteBuffer.wrap(csd[0])); if (csd.length > 1) f.setByteBuffer("csd-1", ByteBuffer.wrap(csd[1]));
    f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
    mc.configure(f, null, null, 0); mc.start();
    int in = mc.dequeueInputBuffer(2000000); ByteBuffer ib = mc.getInputBuffer(in); ib.put(frame);
    mc.queueInputBuffer(in, 0, frame.length, 0, 0);
    in = mc.dequeueInputBuffer(2000000); mc.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
    MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo(); Bitmap out = null; long until = System.currentTimeMillis() + 8000;
    while (System.currentTimeMillis() < until) { int o = mc.dequeueOutputBuffer(bi, 200000);
      if (o >= 0) { if (bi.size > 0) { Image im = mc.getOutputImage(o); out = small(im, tw, th); im.close(); }
        mc.releaseOutputBuffer(o, false); if (out != null || (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break; } }
    mc.stop(); mc.release(); return out;
  }
  static int hi(ByteBuffer b, int i, int ps) { return ps == 2 ? b.get(i + 1) & 0xff : b.get(i) & 0xff; } // 10-bit: top byte
  static Bitmap small(Image im, int tw, int th) {
    Image.Plane[] p = im.getPlanes(); int w = im.getCropRect().width(), h = im.getCropRect().height();
    ByteBuffer Y = p[0].getBuffer(), U = p[1].getBuffer(), V = p[2].getBuffer();
    int ys = p[0].getRowStride(), yp = p[0].getPixelStride(), cs = p[1].getRowStride(), cp = p[1].getPixelStride();
    int[] px = new int[tw * th];
    for (int y = 0; y < th; y++) { int sy = y * h / th; for (int x = 0; x < tw; x++) { int sx = x * w / tw;
      int Yv = hi(Y, sy * ys + sx * yp, yp) - 16, Uv = hi(U, (sy / 2) * cs + (sx / 2) * cp, yp) - 128, Vv = hi(V, (sy / 2) * cs + (sx / 2) * cp, yp) - 128;
      int r = (int) (1.164 * Yv + 1.793 * Vv), g = (int) (1.164 * Yv - 0.213 * Uv - 0.533 * Vv), bb = (int) (1.164 * Yv + 2.112 * Uv);
      px[y * tw + x] = 0xff000000 | clamp(r) << 16 | clamp(g) << 8 | clamp(bb); } }
    return Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888);
  }
  static int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }
  public static void main(String[] a) { try { run(a); } catch (Throwable t) { t.printStackTrace(System.out); } System.exit(0); }
  static void run(String[] a) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(a[0]).openConnection(); c.setRequestProperty("Range", "bytes=0-0"); c.getResponseCode();
    url = c.getURL().toString(); String cr = c.getHeaderField("Content-Range"); size = Long.parseLong(cr.substring(cr.indexOf('/') + 1)); c.disconnect();
    long t0 = System.currentTimeMillis(); open();
    System.out.println("index " + (System.currentTimeMillis() - t0) + " ms, " + got / 1024 + " KB, " + reqs + " reqs; cues " + cues.size() + ", codec " + codec + " " + W + "x" + H);
    byte[][] csd = csd(); int n = Integer.parseInt(a[1]); String dec = a[2];
    for (int i = 1; i <= n; i++) { long[] cue = cues.get(cues.size() * i / (n + 1)); long g0 = got; int r0 = reqs;
      long s = System.currentTimeMillis(); byte[] kf = keyframe(cue); long f = System.currentTimeMillis() - s;
      if (kf == null) { System.out.println("t=" + cue[0] / 1000 + "s no keyframe"); continue; }
      { StringBuilder sb = new StringBuilder("nal len " + nalLen + " types:"); int q = 0; int cnt = 0;
        while (q + nalLen <= kf.length && cnt < 40) { int l = 0; for (int k = 0; k < nalLen; k++) l = l << 8 | (kf[q + k] & 0xff); q += nalLen; if (l <= 0 || q + l > kf.length) { sb.append(" BAD@" + q + " l=" + l); break; } sb.append(" " + ((kf[q] >> 1) & 0x3f) + "/" + l); q += l; cnt++; }
        sb.append(" csd:"); for (int k = 0; k + 4 < csd[0].length; k++) if (csd[0][k] == 0 && csd[0][k+1] == 0 && csd[0][k+2] == 0 && csd[0][k+3] == 1) sb.append(" " + ((csd[0][k+4] >> 1) & 0x3f));
        System.out.println(sb + " first bytes " + String.format("%02x %02x %02x %02x %02x %02x", kf[0], kf[1], kf[2], kf[3], kf[4], kf[5])); }
      s = System.currentTimeMillis(); Bitmap bm = decode(dec, csd, frameAnnexB(kf), 320, 180); long d = System.currentTimeMillis() - s;
      if (bm != null) { FileOutputStream o = new FileOutputStream(a[3] + "/k" + i + ".jpg"); bm.compress(Bitmap.CompressFormat.JPEG, 80, o); o.close(); }
      System.out.println("t=" + cue[0] / 1000 + "s fetch " + f + " ms " + (got - g0) / 1024 + " KB " + (reqs - r0) + " reqs, keyframe " + kf.length / 1024 + " KB, decode " + d + " ms " + (bm != null));
    }
  }
}
