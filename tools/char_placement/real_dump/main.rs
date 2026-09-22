//! Dump real per-line CTC evidence (raw top-K per timestep + emitted geometry)
//! for the vendored recognition conformance fixtures, so the char-placement
//! research can run both algorithms over real inference evidence.
//!
//! Usage: NCNN_PC_DIR=/tmp/opencode/ncnn-install rec_dump <cases_dir> <out.jsonl>

use std::io::Write;

use jpdict_core::models::RecognitionMode;
use jpdict_core::ocr_engine::{recognize_boxes_collect, OcrEngine};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let cases_dir = std::path::PathBuf::from(&args[1]);
    let out_path = &args[2];

    let assets = "/home/holopengin/repos/InstantJPDictDecky/accessibility_daemon/assets";
    let mut engine = OcrEngine::new(assets, RecognitionMode::Both, 4).expect("engine loads");

    let mut names: Vec<_> = std::fs::read_dir(&cases_dir)
        .expect("cases dir")
        .filter_map(|e| e.ok())
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| n.starts_with("recognition-") && n.ends_with(".json"))
        .collect();
    names.sort();

    let mut out = std::io::BufWriter::new(std::fs::File::create(out_path).expect("out file"));

    for name in names {
        let text = std::fs::read_to_string(cases_dir.join(&name)).expect("case reads");
        let v: serde_json::Value = serde_json::from_str(&text).expect("case parses");
        let c = &v["case"];
        let image_rel = c["image"].as_str().expect("image");
        let image_path = cases_dir.parent().unwrap().join(image_rel);
        let img = image::open(&image_path).expect("image loads");
        engine.det_thresh_override = Some(c["det_thresh"].as_f64().unwrap_or(0.25) as f32);
        engine.det_unclip_override = Some(c["det_unclip"].as_f64().unwrap_or(0.7) as f32);
        engine.det_furigana = c["furigana_filter"].as_bool().unwrap_or(false);

        let det = engine.detect_lines(&img).expect("detect");
        eprintln!("{name}: {} det boxes", det.boxes.len());
        let vocab = engine.ppocr_vocab.clone();
        let remap = engine.rec_remap.clone();
        let out_dir = std::env::temp_dir();
        let collected = recognize_boxes_collect(
            &img,
            &det.boxes,
            &det.rotated,
            engine.ppocr_rec.clone(),
            engine.kana_size.clone(),
            &vocab,
            &remap,
            4,
            RecognitionMode::Both,
            &out_dir,
        )
        .expect("recognition");

        for (idx, ann) in collected {
            let quad = ann.quad.as_ref().map(|q| {
                serde_json::json!([q.cx, q.cy, q.w, q.h, q.angle, q.confidence])
            });
            let line = match ann.line.as_ref() {
                Some(l) => serde_json::json!({
                    "text": l.text,
                    "vertical": l.is_vertical,
                    "char_boxes": l.char_boxes.iter()
                        .map(|b| serde_json::json!([b.x, b.y, b.w, b.h])).collect::<Vec<_>>(),
                    "raw_alternatives": l.raw_alternatives.iter()
                        .map(|alts| alts.iter().map(|(ch, s)| serde_json::json!([ch.to_string(), s])).collect::<Vec<_>>())
                        .collect::<Vec<_>>(),
                }),
                None => serde_json::Value::Null,
            };
            let rec = serde_json::json!({
                "case": v["id"],
                "i": idx,
                "bbox": [ann.bbox.x, ann.bbox.y, ann.bbox.w, ann.bbox.h],
                "quad": quad,
                "line": line,
            });
            writeln!(out, "{}", rec).expect("write");
        }
    }
    out.flush().unwrap();
    eprintln!("done -> {out_path}");
}
