//! Vision's Dolby Vision sample rewriter. Own code over the `dolby_vision` crate (MIT, quietvoid).
//!
//! One call per video sample: walks its HEVC NAL units (Annex-B or length-prefixed) and
//!  - converts each Dolby Vision RPU (NAL 62) with libdovi's conversion modes (7 -> 8.1 etc.),
//!  - drops the enhancement layer (NAL 63) when converting or stripping,
//!  - drops the RPU when stripping to the HDR10 base layer,
//!  - drops HDR10+ SEI messages when asked.
//! Everything runs inside catch_unwind: a damaged RPU that makes the parser panic only loses that RPU,
//! it can never take the app down (the stock C build of libdovi aborts the process instead).

use dolby_vision::rpu::dovi_rpu::DoviRpu;
use dolby_vision::rpu::rpu_data_nlq::DoviELType;
use dolby_vision::rpu::extension_metadata::blocks::ExtMetadataBlock;
use std::panic::{catch_unwind, AssertUnwindSafe};

pub const CONVERT: u32 = 1;
pub const STRIP_DV: u32 = 2;
pub const STRIP_HDR10PLUS: u32 = 4;
pub const FALLBACK_MODE1: u32 = 8;
pub const INJECT_HDR10: u32 = 16;

const NAL_SEI_PREFIX: u8 = 39;
const NAL_RPU: u8 = 62;
const NAL_EL: u8 = 63;

fn nal_type(n: &[u8]) -> u8 {
    if n.is_empty() { 255 } else { (n[0] >> 1) & 0x3F }
}

/// NAL units of an Annex-B sample, without their start codes.
fn split_annexb(b: &[u8]) -> Vec<&[u8]> {
    let mut starts = Vec::new(); // index of the first NAL byte after each 00 00 01
    let mut i = 0;
    while i + 2 < b.len() {
        if b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1 {
            starts.push(i + 3);
            i += 3;
        } else {
            i += 1;
        }
    }
    let mut out = Vec::with_capacity(starts.len());
    for (k, &s) in starts.iter().enumerate() {
        let mut e = if k + 1 < starts.len() { starts[k + 1] - 3 } else { b.len() };
        while e > s && b[e - 1] == 0 { e -= 1; } // zero bytes before the next start code are not NAL data
        if e > s { out.push(&b[s..e]); }
    }
    out
}

fn split_lengths(b: &[u8], size: usize) -> Option<Vec<&[u8]>> {
    let mut out = Vec::new();
    let mut p = 0;
    while p + size <= b.len() {
        let mut n = 0usize;
        for k in 0..size { n = (n << 8) | b[p + k] as usize; }
        p += size;
        if n == 0 { continue; }
        if p + n > b.len() { return None; }
        out.push(&b[p..p + n]);
        p += n;
    }
    Some(out)
}

/// True when this prefix SEI NAL opens with an HDR10+ message (ITU-T T.35, USA, Samsung, application 4).
fn is_hdr10plus_sei(n: &[u8]) -> bool {
    if n.len() < 10 || nal_type(n) != NAL_SEI_PREFIX { return false; }
    let mut p = 2;
    let mut ty = 0usize;
    while p < n.len() && n[p] == 0xFF { ty += 255; p += 1; }
    if p >= n.len() { return false; }
    ty += n[p] as usize; p += 1;
    while p < n.len() && n[p] == 0xFF { p += 1; }
    p += 1; // last size byte
    ty == 4 && n.len() >= p + 6 && n[p] == 0xB5 && n[p + 1] == 0x00 && n[p + 2] == 0x3C
        && n[p + 3] == 0x00 && n[p + 4] == 0x01 && n[p + 5] == 0x04
}

/// First SEI payload type of a prefix SEI NAL.
fn sei_first_type(n: &[u8]) -> Option<usize> {
    if nal_type(n) != NAL_SEI_PREFIX { return None; }
    let mut p = 2; let mut ty = 0usize;
    while p < n.len() && n[p] == 0xFF { ty += 255; p += 1; }
    n.get(p).map(|b| ty + *b as usize)
}

/// HDR10 static metadata SEI (mastering display 137 + light level 144) from the RPU's level 6, for the
/// HDR10 base layer when the stream itself carries none. P3-D65 mastering primaries (what UHD masters use).
fn hdr10_sei_from_rpu(rpu_nal: &[u8]) -> Option<Vec<u8>> {
    let (max_l, min_l, cll, fall) = catch_unwind(AssertUnwindSafe(|| {
        let rpu = DoviRpu::parse_unspec62_nalu(rpu_nal).ok()?;
        match rpu.vdr_dm_data.as_ref()?.get_block(6)? {
            ExtMetadataBlock::Level6(b) => Some((b.max_display_mastering_luminance as u32, b.min_display_mastering_luminance as u32,
                b.max_content_light_level, b.max_frame_average_light_level)),
            _ => None,
        }
    })).ok().flatten().unwrap_or((1000, 50, 0, 0));
    let max_l = if max_l == 0 { 1000 } else { max_l };
    let mut rbsp: Vec<u8> = vec![137, 24];
    for v in [13250u16, 34500, 7500, 3000, 34000, 16000, 15635, 16450] { rbsp.extend_from_slice(&v.to_be_bytes()); }
    rbsp.extend_from_slice(&(max_l * 10000).to_be_bytes());
    rbsp.extend_from_slice(&min_l.to_be_bytes()); // level 6 min is already in 0.0001 cd/m2
    if cll > 0 || fall > 0 {
        rbsp.extend_from_slice(&[144, 4]);
        rbsp.extend_from_slice(&cll.to_be_bytes());
        rbsp.extend_from_slice(&fall.to_be_bytes());
    }
    rbsp.push(0x80);
    let mut nal = vec![0x4E, 0x01];
    let mut zeros = 0;
    for b in rbsp {
        if zeros >= 2 && b <= 3 { nal.push(3); zeros = 0; }
        nal.push(b);
        zeros = if b == 0 { zeros + 1 } else { 0 };
    }
    Some(nal)
}

fn convert_rpu(nal: &[u8], mode: u8, fallback: bool) -> Option<Vec<u8>> {
    let once = |m: u8| -> Option<Vec<u8>> {
        catch_unwind(AssertUnwindSafe(|| {
            let mut rpu = DoviRpu::parse_unspec62_nalu(nal).ok()?;
            rpu.convert_with_mode(m).ok()?;
            rpu.write_hevc_unspec62_nalu().ok()
        })).ok().flatten()
    };
    once(mode).or_else(|| if fallback && mode != 1 { once(1) } else { None })
}

/// Rewrites one sample. Returns 0 = leave the sample as it is, >0 = bytes written to `out`, -1 = error,
/// -2 = `out` too small. `counts[0]` += RPUs offered for conversion, `counts[1]` += RPUs converted.
#[no_mangle]
pub unsafe extern "C" fn vl_dovi_sample(
    inp: *const u8, len: usize, annexb: i32, len_size: i32, flags: u32, mode: u8,
    out: *mut u8, cap: usize, counts: *mut i64,
) -> isize {
    if inp.is_null() || out.is_null() || counts.is_null() { return -1; }
    let src = std::slice::from_raw_parts(inp, len);
    let dst = std::slice::from_raw_parts_mut(out, cap);
    let counts = std::slice::from_raw_parts_mut(counts, 2);
    let r = catch_unwind(AssertUnwindSafe(|| {
        let size = len_size.clamp(1, 4) as usize;
        let nals = if annexb != 0 { split_annexb(src) } else { split_lengths(src, size)? };
        let convert = flags & CONVERT != 0;
        let strip = flags & STRIP_DV != 0;
        let plus = flags & STRIP_HDR10PLUS != 0;
        let mut changed = false;
        let mut w = 0usize;
        // HDR10 metadata for the stripped base layer: keyframes only, and only when the stream has none.
        let mut sei: Option<Vec<u8>> = None;
        if strip && flags & INJECT_HDR10 != 0
            && nals.iter().any(|n| (16..=21).contains(&nal_type(n)))
            && !nals.iter().any(|n| sei_first_type(n) == Some(137)) {
            if let Some(r) = nals.iter().find(|n| nal_type(n) == NAL_RPU) { sei = hdr10_sei_from_rpu(r); }
        }
        let mut owned: Vec<Vec<u8>> = Vec::new();
        let mut list: Vec<&[u8]> = Vec::with_capacity(nals.len() + 1);
        if let Some(s) = sei.take() { owned.push(s); }
        let mut placed = owned.is_empty();
        for n in nals.iter() {
            if !placed && nal_type(n) < 32 { list.push(owned[0].as_slice()); placed = true; changed = true; }
            list.push(*n);
        }
        for n in list {
            let t = nal_type(n);
            let mut body: std::borrow::Cow<[u8]> = std::borrow::Cow::Borrowed(n);
            if t == NAL_EL && (convert || strip) { changed = true; continue; }
            if t == NAL_RPU && strip { changed = true; continue; }
            if t == NAL_RPU && convert {
                counts[0] += 1;
                if let Some(v) = convert_rpu(n, mode, flags & FALLBACK_MODE1 != 0) {
                    counts[1] += 1; changed = true; body = std::borrow::Cow::Owned(v);
                }
            }
            if plus && is_hdr10plus_sei(n) { changed = true; continue; }
            let head = if annexb != 0 { 4 } else { size };
            if w + head + body.len() > cap { return Some(-2isize); }
            if annexb != 0 {
                dst[w..w + 4].copy_from_slice(&[0, 0, 0, 1]);
            } else {
                let l = body.len();
                for k in 0..size { dst[w + k] = (l >> (8 * (size - 1 - k))) as u8; }
            }
            w += head;
            dst[w..w + body.len()].copy_from_slice(&body);
            w += body.len();
        }
        Some(if changed { w as isize } else { 0 })
    }));
    match r { Ok(Some(v)) => v, _ => -1 }
}

/// What one RPU NAL says: `info[0]` = profile, `info[1]` = enhancement layer (0 none, 1 MEL, 2 FEL). 0 = ok.
#[no_mangle]
pub unsafe extern "C" fn vl_dovi_info(inp: *const u8, len: usize, info: *mut i32) -> i32 {
    if inp.is_null() || info.is_null() { return -1; }
    let nal = std::slice::from_raw_parts(inp, len);
    let info = std::slice::from_raw_parts_mut(info, 2);
    let r = catch_unwind(AssertUnwindSafe(|| {
        let rpu = DoviRpu::parse_unspec62_nalu(nal).ok()?;
        let el = match rpu.get_enhancement_layer_type() { Some(DoviELType::FEL) => 2, Some(DoviELType::MEL) => 1, None => 0 };
        Some((rpu.dovi_profile as i32, el))
    }));
    match r {
        Ok(Some((p, e))) => { info[0] = p; info[1] = e; 0 }
        _ => -1,
    }
}
