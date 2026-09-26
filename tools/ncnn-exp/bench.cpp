// ncnn x86 experiment harness for the InstantJPDict det/rec pair.
//
// Loads the shipped models (never modified), synthesises the exact runtime
// shapes (det 896x896 letterboxed, rec 48xW dynamic width), and times
// `Extractor::extract` under a fully specified ncnn::Option. One config per
// process, so nothing leaks between configs; a driver interleaves configs and
// takes medians (see sweep.py).
//
// What makes a number trustworthy:
//   * `--iters` timed runs after `--warmup` untimed ones, and the *first two
//     timed runs must be bitwise identical* (`det=ok/UNSTABLE`), otherwise the
//     run is reported as untrusted. ncnn packs weights lazily per Net, so an
//     un-warmed first run is both slower and a different tensor.
//   * a checksum of the output tensor (FNV-1a over the raw float bits) so any
//     config that changes the answer is caught by the driver, not by eye.
//   * `--ref <file>` compares against a reference output: exact bitwise match,
//     max abs diff, and a task-shaped metric — mask IoU at a DB threshold for
//     det, greedy CTC argmax + decoded text for rec (which is what the app
//     actually consumes; two nets can differ in float noise and still decode
//     the same string, and only the latter matters).
//
// Usage:
//   bench --model det --iters 10 --warmup 2 --dump out.bin
//   bench --model rec --width 432 --iters 10 --set threads=1,packing=1
//   bench --model rec --width 432 --ref base.bin --vocab vocab.json
//
// Output: one TSV row (see the printf at the bottom) so the driver can diff
// configs without parsing prose.

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <algorithm>
#include <cmath>
#include <numeric>
#include <string>
#include <vector>

#include "net.h"
#include "cpu.h"
#include "mat.h"
#include "option.h"
#include "layer.h"
#include "benchmark.h" // ncnn::get_current_time()

struct Config
{
    std::string model;   // "det" | "rec"
    std::string dir;     // model dir
    int width = 0;       // rec input width (mult of 8); 0 = det default
    int iters = 10;
    int warmup = 2;
    int threads = -1;    // -1 = model default
    bool has_threads = false;
    std::string outblob; // default: det "out0", rec "191"
    std::string dump;
    std::string ref;
    std::string vocab;
    std::string remap;
    std::string input;    // mkraw.py dump of a real page / line
    int cropX = 0, cropY = 0, cropW = 0, cropH = 0;
    bool has_crop = false;
    bool fill = false;
    int det_size = 896;
    // option overrides, applied after the model default
    int winograd = -1, sgemm = -1, im2col = -1, packing = -1;
    int fp16_packed = -1, fp16_storage = -1, fp16_arithmetic = -1;
    int bf16_storage = -1, bf16_packed = -1;
    int int8_inference = -1;
    int int8_packed = -1, int8_storage = -1, int8_arithmetic = -1;
    int wino23 = -1, wino43 = -1, wino63 = -1;
    int a53a55 = -1, fp16_uniform = -1, flush_denormals = -1;
    int light = 1;
    int powersave = 0;
    int openmp_blocktime = -1;
};

static void die(const char* fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "bench: ");
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
    exit(2);
}

static int parse_kv_option(const std::string& kv)
{
    // returns -1 for "unset", 0/1 otherwise
    if (kv == "1" || kv == "true" || kv == "on" || kv == "yes") return 1;
    if (kv == "0" || kv == "false" || kv == "off" || kv == "no") return 0;
    return -1;
}

// ── synthetic input ────────────────────────────────────────────────────────
//
// det: the app letterboxes the page into 896x896 with gray-128 padding and
// ImageNet normalisation. Reproduce the *value distribution* (a mostly flat
// gray field with dark text strokes), not a specific page: the point is a
// representative activation range, and the ink pattern keeps the dynamic
// quantizer and the DB sigmoid off their rails. Deterministic, no RNG state
// shared between runs.
static void det_norm(int channel, int v, float* out)
{
    const float f = (float)v / 255.0f;
    *out = (f - (channel == 0 ? 0.485f : channel == 1 ? 0.456f : 0.406f))
        / (channel == 0 ? 0.229f : channel == 1 ? 0.224f : 0.225f);
}

static ncnn::Mat make_det_input(int size)
{
    ncnn::Mat in(size, size, 3);
    // gray 128 padding everywhere
    for (int c = 0; c < 3; c++) {
        float pad;
        det_norm(c, 128, &pad);
        float* p = in.channel(c);
        for (int i = 0; i < size * size; i++) p[i] = pad;
    }
    // content: a text-like field occupying the middle 64% of the canvas
    const int x0 = size * 18 / 100, x1 = size * 82 / 100;
    const int y0 = size * 6 / 100, y1 = size * 94 / 100;
    const int line_h = size / 64; // ~ text line pitch
    const int gap = line_h / 3;
    for (int y = y0; y < y1; y += line_h + gap) {
        const int ly = y + line_h;
        if (ly > y1) break;
        // deterministic "glyphs": vertical strokes of varying darkness
        int x = x0 + (y * 7919) % 13;
        while (x < x1 - 2) {
            const int gw = 2 + (x * 104729 + y) % 5;
            const int dark = 20 + (x * 31 + y * 17) % 60; // ink darkness
            for (int yy = y; yy < ly && yy < y1; yy++) {
                for (int xx = x; xx < x + gw && xx < x1; xx++) {
                    const int v = dark;
                    for (int c = 0; c < 3; c++) {
                        float f;
                        det_norm(c, v, &f);
                        in.channel(c)[yy * size + xx] = f;
                    }
                }
            }
            x += gw + 3 + (x * 13 + y) % 7;
        }
    }
    return in;
}

// rec: 48 x W, 3 channels. PP-OCR normalise gray/127.5-1, and the app leaves
// the columns [targetW, modelW) of the mult-of-8 padding at 0.0f (buildRecInput
// only writes [0, contentW) into a zero-filled array) — so the pad is 0.0, NOT
// -1.0. Reproduced here because it is part of what the model sees.
static void rec_norm(int r, int g, int b, float* out)
{
    const float gray = 0.299f * r + 0.587f * g + 0.114f * b;
    *out = gray / 127.5f - 1.0f;
}

// A real page / text line, loaded from a `mkraw.py` dump (see that file for the
// header). The geometry is the app's, not an approximation of it:
//
//   det: letterbox to `size` x `size`, aspect preserved, gray-128 padding
//        *before* normalisation (so the pad is det_norm(128) per channel), then
//        per-channel ImageNet normalisation.
//   rec: the line's tight crop scaled to (targetW x 48) with
//        targetW = rw*48/rh, modelW = ceil(targetW/8)*8, [targetW, modelW) = 0.
struct RawImage
{
    int w = 0, h = 0, c = 0;
    std::vector<unsigned char> px;
    const unsigned char* at(int x, int y) const
    {
        return &px[((size_t)y * w + x) * c];
    }
};

static bool load_raw(const std::string& path, RawImage* img)
{
    FILE* fp = fopen(path.c_str(), "rb");
    if (!fp) return false;
    char magic[4] = {0};
    if (fread(magic, 1, 4, fp) != 4 || memcmp(magic, "RAW1", 4) != 0) {
        fclose(fp);
        return false;
    }
    int32_t w, h, c, bits;
    if (fread(&w, 4, 1, fp) != 1 || fread(&h, 4, 1, fp) != 1
        || fread(&c, 4, 1, fp) != 1 || fread(&bits, 4, 1, fp) != 1) {
        fclose(fp);
        return false;
    }
    img->w = w;
    img->h = h;
    img->c = c;
    img->px.resize((size_t)w * h * c);
    const size_t got = fread(img->px.data(), 1, img->px.size(), fp);
    fclose(fp);
    return got == img->px.size();
}

static ncnn::Mat make_det_input_from(const RawImage& img, int size)
{
    // longest side -> size, aspect preserved (integer geometry like the app's
    // resizeW/resizeH/padX/padY), bilinear like Canvas' default filtering
    const double scale = (double)size / (double)(img.w > img.h ? img.w : img.h);
    int rw = (int)(img.w * scale + 0.5);
    int rh = (int)(img.h * scale + 0.5);
    if (rw > size) rw = size;
    if (rh > size) rh = size;
    const int padX = (size - rw) / 2;
    const int padY = (size - rh) / 2;

    ncnn::Mat in(size, size, 3);
    for (int ch = 0; ch < 3; ch++) {
        float padv;
        det_norm(ch, 128, &padv);
        float* dst = in.channel(ch);
        for (int i = 0; i < size * size; i++) dst[i] = padv;
    }
    for (int y = 0; y < rh; y++) {
        // source coordinate of the destination pixel centre
        const double sy = ((double)(y + padY) + 0.5) / scale - 0.5;
        const int y0 = (int)std::floor(sy);
        const double fy = sy - y0;
        const int y1 = std::min(y0 + 1, img.h - 1);
        const int ya = std::max(0, std::min(y0, img.h - 1));
        for (int x = 0; x < rw; x++) {
            const double sx = ((double)(x + padX) + 0.5) / scale - 0.5;
            const int x0 = (int)std::floor(sx);
            const double fx = sx - x0;
            const int xa = std::max(0, std::min(x0, img.w - 1));
            const int xb = std::min(x0 + 1, img.w - 1);
            const double w00 = (1 - fx) * (1 - fy), w01 = (1 - fx) * fy;
            const double w10 = fx * (1 - fy), w11 = fx * fy;
            for (int ch = 0; ch < 3; ch++) {
                const double v = w00 * img.at(xa, ya)[ch] + w01 * img.at(xa, y1)[ch]
                    + w10 * img.at(xb, ya)[ch] + w11 * img.at(xb, y1)[ch];
                int vv = (int)(v + 0.5);
                if (vv < 0) vv = 0;
                if (vv > 255) vv = 255;
                float f;
                det_norm(ch, vv, &f);
                in.channel(ch)[(y + padY) * size + (x + padX)] = f;
            }
        }
    }
    return in;
}

static ncnn::Mat make_rec_input_from(const RawImage& img, int cropX, int cropY, int cropW, int cropH, int modelW, bool fill)
{
    const int H = 48;
    // fill=false: the app's own geometry, targetW = rw*48/rh, zero-padded up to
    // the mult-of-8 modelW. fill=true: stretch the crop over the whole modelW,
    // which is what a line whose *natural* width is modelW would produce — used
    // to hit an exact width bucket with a real line instead of a padded one.
    const int targetW = fill ? modelW
                             : std::min(modelW, std::max(1, (int)((double)cropW * H / (double)cropH + 0.5)));
    ncnn::Mat in(modelW, H, 3);
    for (int ch = 0; ch < 3; ch++) {
        float* dst = in.channel(ch);
        for (int i = 0; i < modelW * H; i++) dst[i] = 0.0f; // app's zero pad
        for (int y = 0; y < H; y++) {
            const double sy = ((double)y + 0.5) * cropH / H - 0.5;
            const int y0 = (int)std::floor(sy);
            const double fy = sy - y0;
            const int ya = std::max(0, std::min(y0, cropH - 1));
            const int yb = std::min(y0 + 1, cropH - 1);
            for (int x = 0; x < targetW; x++) {
                const double sx = ((double)x + 0.5) * cropW / targetW - 0.5;
                const int x0 = (int)std::floor(sx);
                const double fx = sx - x0;
                const int xa = std::max(0, std::min(x0, cropW - 1));
                const int xb = std::min(x0 + 1, cropW - 1);
                const double w00 = (1 - fx) * (1 - fy), w01 = (1 - fx) * fy;
                const double w10 = fx * (1 - fy), w11 = fx * fy;
                for (int ch2 = 0; ch2 < 3; ch2++) {
                    const double v = w00 * img.at(cropX + xa, cropY + ya)[ch2]
                        + w01 * img.at(cropX + xa, cropY + yb)[ch2]
                        + w10 * img.at(cropX + xb, cropY + ya)[ch2]
                        + w11 * img.at(cropX + xb, cropY + yb)[ch2];
                    int vv = (int)(v + 0.5);
                    if (vv < 0) vv = 0;
                    if (vv > 255) vv = 255;
                    float f;
                    rec_norm(vv, vv, vv, &f);
                    dst[y * modelW + x] = f;
                }
            }
        }
    }
    return in;
}

// Synthetic fallback, used when no --input is given. Kept so a run is still
// possible without a PNG, but it is NOT a parity-grade input: the rec net
// decodes it to all-blank, so a text comparison against it proves nothing.
static ncnn::Mat make_rec_input(int w, int h)
{
    ncnn::Mat in(w, h, 3);
    for (int c = 0; c < 3; c++) {
        float bg, ink;
        det_norm(c, 240, &bg);
        det_norm(c, 30, &ink);
        float* p = in.channel(c);
        for (int i = 0; i < w * h; i++) p[i] = bg;
        // stroke band in the vertical middle, glyph pitch ~ w/16
        const int top = h / 3, bot = 2 * h / 3;
        const int pitch = w / 16 < 1 ? 1 : w / 16;
        for (int gx = 0; gx < w; gx += pitch) {
            const int gw = pitch / 2 < 1 ? 1 : pitch / 2;
            for (int y = top; y < bot; y++) {
                for (int x = gx; x < gx + gw && x < w; x++) {
                    p[y * w + x] = ink;
                }
            }
        }
    }
    return in;
}

// ── output analysis ────────────────────────────────────────────────────────

static unsigned long long fnv1a(const void* data, size_t n)
{
    const unsigned char* p = (const unsigned char*)data;
    unsigned long long h = 1469598103934665603ULL;
    for (size_t i = 0; i < n; i++) {
        h ^= p[i];
        h *= 1099511628211ULL;
    }
    return h;
}

static std::vector<float> read_floats(const std::string& path, size_t* count)
{
    FILE* fp = fopen(path.c_str(), "rb");
    if (!fp) return std::vector<float>();
    fseek(fp, 0, SEEK_END);
    long n = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    std::vector<float> v(n / 4);
    size_t got = fread(v.data(), 1, n, fp);
    fclose(fp);
    *count = got / 4;
    return v;
}

// DB probability map: the boxes the app extracts are connected components of
// {p > thresh}. Comparing those masks at the app's own threshold is a much
// sharper parity test than a float diff, and it is the quantity the det
// quality gate is stated in (box IoU).
static double mask_iou(const std::vector<float>& a, const std::vector<float>& b, float thresh)
{
    if (a.empty() || b.empty() || a.size() != b.size()) return -1.0;
    size_t inter = 0, uni = 0;
    for (size_t i = 0; i < a.size(); i++) {
        const bool pa = a[i] > thresh, pb = b[i] > thresh;
        if (pa && pb) inter++;
        if (pa || pb) uni++;
    }
    return uni == 0 ? 1.0 : (double)inter / (double)uni;
}

// rec: what the app consumes is the greedy CTC argmax per timestep with blank
// (id 0) collapse. Report both the argmax agreement and the decoded string so
// a config that changes float noise but not text is visibly distinguishable
// from one that changes the answer.
static std::string greedy_ctc(const std::vector<float>& logits, int seqLen, int numClasses,
                              const std::vector<int>& remap, const std::vector<std::string>& vocab,
                              int* argmax_changed, int* argmax_total)
{
    std::string s;
    int prev = -1;
    for (int t = 0; t < seqLen; t++) {
        const float* row = logits.data() + (size_t)t * numClasses;
        int best = 0;
        for (int k = 1; k < numClasses; k++) {
            if (row[k] > row[best]) best = k;
        }
        (*argmax_total)++;
        if (best != prev) (*argmax_changed)++;
        if (best != 0 && best != prev) {
            const int id = best - 1 < (int)remap.size() ? remap[best - 1] : -1;
            if (id >= 0 && id < (int)vocab.size()) {
                // vocab entries can be multi-byte utf8; append raw bytes
                s += vocab[id];
            } else {
                s += '?';
            }
        }
        prev = best;
    }
    return s;
}

static bool load_vocab(const std::string& vocabPath, const std::string& remapPath,
                       std::vector<std::string>* vocab, std::vector<int>* remap)
{
    if (vocabPath.empty()) return true;
    FILE* fp = fopen(vocabPath.c_str(), "rb");
    if (!fp) return false;
    std::string s;
    char buf[4096];
    size_t got;
    while ((got = fread(buf, 1, sizeof(buf), fp)) > 0) s.append(buf, got);
    fclose(fp);
    // minimal json string-array parser: ["a","b",...] with \uXXXX escapes
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        if (s[i] != '"') {
            i++;
            continue;
        }
        i++;
        std::string cur;
        while (i < n && s[i] != '"') {
            if (s[i] == '\\' && i + 1 < n) {
                const char e = s[i + 1];
                i += 2;
                if (e == 'u') {
                    unsigned cp = 0;
                    for (int k = 0; k < 4 && i < n; k++, i++) {
                        const char h = s[i];
                        cp <<= 4;
                        if (h >= '0' && h <= '9') cp |= h - '0';
                        else if (h >= 'a' && h <= 'f') cp |= h - 'a' + 10;
                        else if (h >= 'A' && h <= 'F') cp |= h - 'A' + 10;
                    }
                    // utf-8 encode (surrogate pairs are not in this vocab slice
                    // after the #44 re-prune; a lone surrogate becomes U+FFFD)
                    if (cp >= 0xD800 && cp <= 0xDFFF) cp = 0xFFFD;
                    if (cp < 0x80) cur += (char)cp;
                    else if (cp < 0x800) {
                        cur += (char)(0xC0 | (cp >> 6));
                        cur += (char)(0x80 | (cp & 0x3F));
                    } else {
                        cur += (char)(0xE0 | (cp >> 12));
                        cur += (char)(0x80 | ((cp >> 6) & 0x3F));
                        cur += (char)(0x80 | (cp & 0x3F));
                    }
                } else {
                    cur += e;
                }
            } else {
                cur += s[i++];
            }
        }
        i++; // closing quote
        vocab->push_back(cur);
    }
    if (!remapPath.empty()) {
        FILE* rf = fopen(remapPath.c_str(), "rb");
        if (!rf) return false;
        int v;
        while (fscanf(rf, "%d", &v) == 1) remap->push_back(v);
        fclose(rf);
    }
    return true;
}

int main(int argc, char** argv)
{
    Config cfg;
    for (int i = 1; i < argc; i++) {
        const std::string a = argv[i];
        auto next = [&](void) -> std::string {
            if (i + 1 >= argc) die("missing value for %s", a.c_str());
            return argv[++i];
        };
        if (a == "--model") cfg.model = next();
        else if (a == "--dir") cfg.dir = next();
        else if (a == "--width") cfg.width = atoi(next().c_str());
        else if (a == "--iters") cfg.iters = atoi(next().c_str());
        else if (a == "--warmup") cfg.warmup = atoi(next().c_str());
        else if (a == "--threads") { cfg.threads = atoi(next().c_str()); cfg.has_threads = true; }
        else if (a == "--outblob") cfg.outblob = next();
        else if (a == "--dump") cfg.dump = next();
        else if (a == "--ref") cfg.ref = next();
        else if (a == "--vocab") cfg.vocab = next();
        else if (a == "--remap") cfg.remap = next();
        else if (a == "--det-size") cfg.det_size = atoi(next().c_str());
        else if (a == "--fill") cfg.fill = true;
        else if (a == "--input") cfg.input = next();
        else if (a == "--crop") {
            // x,y,w,h of the line's tight crop inside --input
            const std::string v = next();
            sscanf(v.c_str(), "%d,%d,%d,%d", &cfg.cropX, &cfg.cropY, &cfg.cropW, &cfg.cropH);
            cfg.has_crop = true;
        }
        else if (a == "--light") cfg.light = atoi(next().c_str());
        else if (a == "--powersave") cfg.powersave = atoi(next().c_str());
        else if (a == "--set") {
            // comma separated k=v
            std::string list = next();
            size_t pos = 0;
            while (pos < list.size()) {
                size_t comma = list.find(',', pos);
                if (comma == std::string::npos) comma = list.size();
                std::string item = list.substr(pos, comma - pos);
                pos = comma + 1;
                if (item.empty()) continue;
                size_t eq = item.find('=');
                if (eq == std::string::npos) die("bad --set item %s", item.c_str());
                const std::string k = item.substr(0, eq);
                const std::string v = item.substr(eq + 1);
                const int b = parse_kv_option(v);
                if (k == "threads") { cfg.threads = atoi(v.c_str()); cfg.has_threads = true; }
                else if (k == "winograd") cfg.winograd = b;
                else if (k == "sgemm") cfg.sgemm = b;
                else if (k == "im2col") cfg.im2col = b;
                else if (k == "packing") cfg.packing = b;
                else if (k == "fp16_packed") cfg.fp16_packed = b;
                else if (k == "fp16_storage") cfg.fp16_storage = b;
                else if (k == "fp16_arithmetic") cfg.fp16_arithmetic = b;
                else if (k == "bf16_storage") cfg.bf16_storage = b;
                else if (k == "bf16_packed") cfg.bf16_packed = b;
                else if (k == "int8") cfg.int8_inference = b;
                else if (k == "int8_packed") cfg.int8_packed = b;
                else if (k == "int8_storage") cfg.int8_storage = b;
                else if (k == "int8_arithmetic") cfg.int8_arithmetic = b;
                else if (k == "wino23") cfg.wino23 = b;
                else if (k == "wino43") cfg.wino43 = b;
                else if (k == "wino63") cfg.wino63 = b;
                else if (k == "a53a55") cfg.a53a55 = b;
                else if (k == "fp16_uniform") cfg.fp16_uniform = b;
                else if (k == "denormals") cfg.flush_denormals = atoi(v.c_str());
                else if (k == "light") cfg.light = atoi(v.c_str());
                else if (k == "blocktime") cfg.openmp_blocktime = atoi(v.c_str());
                else if (k == "powersave") cfg.powersave = atoi(v.c_str());
                else die("unknown --set key %s", k.c_str());
            }
        } else {
            die("unknown arg %s", a.c_str());
        }
    }
    if (cfg.model.empty() || cfg.dir.empty()) die("--model and --dir required");
    if (cfg.model != "det" && cfg.model != "rec") die("--model must be det or rec");

    const std::string param = cfg.dir + "/" + (cfg.model == "det" ? "det.param" : "rec_dyn.param");
    const std::string bin = cfg.dir + "/" + (cfg.model == "det" ? "det.bin" : "rec_dyn.bin");

    ncnn::set_cpu_powersave(cfg.powersave);

    ncnn::Net net;
    ncnn::Option& opt = net.opt;

    // model defaults = exactly what the app ships
    if (cfg.model == "det") {
        opt.num_threads = 2;
        opt.use_fp16_packed = true;
        opt.use_fp16_storage = true;
        opt.use_fp16_arithmetic = true;
        opt.use_packing_layout = true;
    } else {
        opt.num_threads = 1;
        opt.use_fp16_packed = false;
        opt.use_fp16_storage = false;
        opt.use_fp16_arithmetic = false;
        opt.use_packing_layout = true;
    }
    if (cfg.has_threads) opt.num_threads = cfg.threads;
    if (cfg.winograd >= 0) opt.use_winograd_convolution = cfg.winograd;
    if (cfg.sgemm >= 0) opt.use_sgemm_convolution = cfg.sgemm;
    if (cfg.im2col >= 0) { /* no such Option field in this fork: recorded by driver */ }
    if (cfg.packing >= 0) opt.use_packing_layout = cfg.packing;
    if (cfg.fp16_packed >= 0) opt.use_fp16_packed = cfg.fp16_packed;
    if (cfg.fp16_storage >= 0) opt.use_fp16_storage = cfg.fp16_storage;
    if (cfg.fp16_arithmetic >= 0) opt.use_fp16_arithmetic = cfg.fp16_arithmetic;
    if (cfg.bf16_storage >= 0) opt.use_bf16_storage = cfg.bf16_storage;
    if (cfg.bf16_packed >= 0) opt.use_bf16_packed = cfg.bf16_packed;
    if (cfg.int8_inference >= 0) opt.use_int8_inference = cfg.int8_inference;
    if (cfg.int8_packed >= 0) opt.use_int8_packed = cfg.int8_packed;
    if (cfg.int8_storage >= 0) opt.use_int8_storage = cfg.int8_storage;
    if (cfg.int8_arithmetic >= 0) opt.use_int8_arithmetic = cfg.int8_arithmetic;
    if (cfg.wino23 >= 0) opt.use_winograd23_convolution = cfg.wino23;
    if (cfg.wino43 >= 0) opt.use_winograd43_convolution = cfg.wino43;
    if (cfg.wino63 >= 0) opt.use_winograd63_convolution = cfg.wino63;
    if (cfg.a53a55 >= 0) opt.use_a53_a55_optimized_kernel = cfg.a53a55;
    if (cfg.fp16_uniform >= 0) opt.use_fp16_uniform = cfg.fp16_uniform;
    if (cfg.flush_denormals >= 0) opt.flush_denormals = (unsigned char)cfg.flush_denormals;
    if (cfg.openmp_blocktime >= 0) opt.openmp_blocktime = cfg.openmp_blocktime;

    if (net.load_param(param.c_str()) != 0) die("load_param %s failed", param.c_str());
    if (net.load_model(bin.c_str()) != 0) die("load_model %s failed", bin.c_str());

    const int h = cfg.model == "det" ? cfg.det_size : 48;
    const int w = cfg.model == "det" ? cfg.det_size : cfg.width;
    if (cfg.model == "rec" && (w <= 0 || w % 8 != 0)) die("rec width must be a positive multiple of 8");

    std::string outblob = cfg.outblob;
    if (outblob.empty()) outblob = cfg.model == "det" ? "out0" : "191";

    ncnn::Mat in;
    if (!cfg.input.empty()) {
        RawImage img;
        if (!load_raw(cfg.input, &img)) die("load_raw %s failed", cfg.input.c_str());
        if (cfg.model == "det") {
            in = make_det_input_from(img, w);
        } else {
            int cx = 0, cy = 0, cw = img.w, chh = img.h;
            if (cfg.has_crop) {
                cx = cfg.cropX;
                cy = cfg.cropY;
                cw = cfg.cropW;
                chh = cfg.cropH;
            }
            if (cw <= 0 || chh <= 0 || cx + cw > img.w || cy + chh > img.h) {
                die("crop %d,%d %dx%d outside %dx%d image", cx, cy, cw, chh, img.w, img.h);
            }
            in = make_rec_input_from(img, cx, cy, cw, chh, w, cfg.fill);
        }
    } else {
        in = cfg.model == "det" ? make_det_input(w) : make_rec_input(w, h);
    }

    std::vector<double> ms;
    ms.reserve(cfg.iters);
    std::vector<float> first, second;
    bool stable = true;
    int out_dims = 0, out_w = 0, out_h = 0, out_c = 0;
    for (int it = 0; it < cfg.warmup + cfg.iters; it++) {
        ncnn::Extractor ex = net.create_extractor();
        ex.set_light_mode(cfg.light != 0);
        if (ex.input("in0", in) != 0) die("input failed");
        ncnn::Mat out;
        const double t0 = ncnn::get_current_time();
        const int ret = ex.extract(outblob.c_str(), out);
        const double t1 = ncnn::get_current_time();
        if (ret != 0) die("extract(%s) failed %d", outblob.c_str(), ret);
        out_dims = out.dims;
        out_w = out.w;
        out_h = out.h;
        out_c = out.c;
        // Mat::total() is cstep*c, i.e. it includes the 16-byte tail padding a
        // 2-D blob gets (rec 13353x54 -> 721064, not 721062). The app reads
        // w*h for a 2-D logits tensor (recClassWidth takes out.w and multiplies
        // by seqLen), so the harness must do the same or its row stride drifts.
        size_t nelem = 1;
        if (out.dims == 1) nelem = (size_t)out.w;
        else if (out.dims == 2) nelem = (size_t)out.w * out.h;
        else if (out.dims == 3) nelem = (size_t)out.w * out.h * out.c;
        else nelem = (size_t)out.w * out.h * out.d * out.c;
        if (it == cfg.warmup) first.assign((const float*)out.data, (const float*)out.data + nelem);
        else if (it == cfg.warmup + 1) second.assign((const float*)out.data, (const float*)out.data + nelem);
        if (it >= cfg.warmup) ms.push_back(t1 - t0);
    }
    if (first != second) stable = false;

    std::vector<double> sorted = ms;
    std::sort(sorted.begin(), sorted.end());
    const double med = sorted[sorted.size() / 2];
    const double mn = sorted.front();
    const double p90 = sorted[(size_t)(sorted.size() * 0.9)];
    const double mean = std::accumulate(ms.begin(), ms.end(), 0.0) / ms.size();

    // ── decode + parity ──
    //
    // rec is always decoded (with --vocab/--remap), not only when a reference
    // is supplied: the decoded string is the app's actual output, so it is the
    // parity gate that matters, and a config that changes the text is dead on
    // arrival however small the float difference is.
    double maxdiff = -1, iou = -1;
    int argmax_changed = -1, argmax_total = 0;
    std::string text_self, text_ref;
    bool bitidentical = false;
    size_t refcount = 0;

    std::vector<int> remap;
    std::vector<std::string> vocab;
    if (!cfg.vocab.empty() && !load_vocab(cfg.vocab, cfg.remap, &vocab, &remap)) {
        fprintf(stderr, "bench: vocab/remap load failed\n");
    }
    if (cfg.model == "rec" && !vocab.empty()) {
        const int seqLen = w / 8;
        const int numClasses = (int)(first.size() / (seqLen ? seqLen : 1)); // 2-D: w innermost
        int ch1 = 0;
        text_self = greedy_ctc(first, seqLen, numClasses, remap, vocab, &ch1, &argmax_total);
    }

    if (!cfg.ref.empty()) {
        std::vector<float> ref = read_floats(cfg.ref, &refcount);
        if (ref.size() != first.size()) {
            maxdiff = -2; // shape mismatch
        } else {
            maxdiff = 0;
            for (size_t i = 0; i < ref.size(); i++) {
                const double d = std::fabs((double)ref[i] - (double)first[i]);
                if (d > maxdiff) maxdiff = d;
            }
            bitidentical = memcmp(ref.data(), first.data(), ref.size() * 4) == 0;
            if (cfg.model == "det") {
                iou = mask_iou(ref, first, 0.3f);
            } else {
                const int seqLen = w / 8;
                const int numClasses = (int)(first.size() / (seqLen ? seqLen : 1));
                if (!vocab.empty()) {
                    int ch2 = 0, tot2 = 0;
                    text_ref = greedy_ctc(ref, seqLen, numClasses, remap, vocab, &ch2, &tot2);
                }
                int diffSteps = 0;
                for (int t = 0; t < seqLen; t++) {
                    const float* a = first.data() + (size_t)t * numClasses;
                    const float* b = ref.data() + (size_t)t * numClasses;
                    int ba = 0, bb = 0;
                    for (int k = 1; k < numClasses; k++) {
                        if (a[k] > a[ba]) ba = k;
                        if (b[k] > b[bb]) bb = k;
                    }
                    if (ba != bb) diffSteps++;
                }
                argmax_changed = diffSteps;
            }
        }
    }

    if (!cfg.dump.empty()) {
        FILE* fp = fopen(cfg.dump.c_str(), "wb");
        if (!fp) die("dump open failed");
        fwrite(first.data(), 4, first.size(), fp);
        fclose(fp);
    }

    printf("model\t%s\tw\t%d\tmed_ms\t%.3f\tmin_ms\t%.3f\tp90_ms\t%.3f\tmean_ms\t%.3f\t"
           "iters\t%d\tstable\t%s\tbitident\t%d\tout_elems\t%zu\tdims\t%d\tow\t%d\toh\t%d\toc\t%d\t"
           "ck\t%016llx\tmaxabs\t%.6g\tiou03\t%.6f\t"
           "argmax_diff\t%d\tthreads\t%d\ttext\t%s\ttextref\t%s\n",
           cfg.model.c_str(), w, med, mn, p90, mean, cfg.iters,
           stable ? "ok" : "UNSTABLE", bitidentical ? 1 : 0, first.size(),
           out_dims, out_w, out_h, out_c,
           (unsigned long long)fnv1a(first.data(), first.size() * 4), maxdiff, iou,
           argmax_changed, net.opt.num_threads, text_self.c_str(), text_ref.c_str());
    return 0;
}
