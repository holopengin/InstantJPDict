#!/usr/bin/env python3
"""PNG -> raw uint8 conversion for the ncnn experiment harness.

Why this exists: the harness needs *real* pixels (a real page, a real text
line) so that parity checks have teeth — with a synthetic bar pattern the rec
net emits blank at every timestep, so any config "passes" the text comparison
while producing a completely different tensor. Python's stdlib zlib is the only
PNG decoder available on this host (no PIL, no numpy), so the decode lives here
and the tensor geometry lives in the C++ harness, next to the app's own
normalisation.

    mkraw.py <in.png> <out.raw>

Output format (little endian): magic "RAW1", int32 w, int32 h, int32 channels,
int32 bits (8), then w*h*channels bytes of pixel data, row-major, channel
innermost. RGB and RGBA and gray inputs are all accepted; the channels are
kept as-is so the harness can apply the app's own gray weights.
"""
import struct
import sys
import zlib


def read_png(path):
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit("%s: not a PNG" % path)
    pos = 8
    idat = b""
    w = h = bitdepth = colortype = None
    interlace = 0
    palette = None
    trns = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos : pos + 4])
        ctype = data[pos + 4 : pos + 8]
        body = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            w, h, bitdepth, colortype, _comp, _filt, interlace = struct.unpack(">IIBBBBB", body)
        elif ctype == b"PLTE":
            palette = body
        elif ctype == b"tRNS":
            trns = body
        elif ctype == b"IDAT":
            idat += body
        elif ctype == b"IEND":
            break
    if interlace:
        raise SystemExit("%s: interlaced PNG unsupported" % path)
    if bitdepth != 8:
        raise SystemExit("%s: bit depth %d unsupported (need 8)" % (path, bitdepth))
    nch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[colortype]
    raw = zlib.decompress(idat)
    stride = w * nch
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        ft = raw[p]
        p += 1
        line = bytearray(raw[p : p + stride])
        p += stride
        if ft == 0:
            pass
        elif ft == 1:  # Sub
            for i in range(nch, stride):
                line[i] = (line[i] + line[i - nch]) & 0xFF
        elif ft == 2:  # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ft == 3:  # Average
            for i in range(stride):
                a = line[i - nch] if i >= nch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif ft == 4:  # Paeth
            for i in range(stride):
                a = line[i - nch] if i >= nch else 0
                b = prev[i]
                c = prev[i - nch] if i >= nch else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        else:
            raise SystemExit("%s: bad filter %d" % (path, ft))
        out[y * stride : (y + 1) * stride] = line
        prev = line
    # normalise every input to 3 channels
    if colortype == 2:
        rgb = bytes(out)
    elif colortype == 6:
        rgb = bytearray(w * h * 3)
        for i in range(w * h):
            rgb[i * 3 : i * 3 + 3] = out[i * 4 : i * 4 + 3]
        rgb = bytes(rgb)
    elif colortype == 0:
        rgb = bytearray(w * h * 3)
        for i in range(w * h):
            rgb[i * 3 : i * 3 + 3] = bytes([out[i]]) * 3
        rgb = bytes(rgb)
    elif colortype == 3:
        rgb = bytearray(w * h * 3)
        for i in range(w * h):
            idx = out[i]
            rgb[i * 3 : i * 3 + 3] = palette[idx * 3 : idx * 3 + 3]
        rgb = bytes(rgb)
    else:  # 4 = gray+alpha
        rgb = bytearray(w * h * 3)
        for i in range(w * h):
            rgb[i * 3 : i * 3 + 3] = bytes([out[i * 2]]) * 3
        rgb = bytes(rgb)
    return w, h, rgb


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: mkraw.py <in.png> <out.raw>")
    w, h, rgb = read_png(sys.argv[1])
    with open(sys.argv[2], "wb") as fp:
        fp.write(b"RAW1")
        fp.write(struct.pack("<iiii", w, h, 3, 8))
        fp.write(rgb)
    print("%s -> %s (%dx%d rgb8)" % (sys.argv[1], sys.argv[2], w, h))


if __name__ == "__main__":
    main()
