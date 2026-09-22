//! Dump real per-line CTC evidence (raw top-K per timestep + emitted geometry)
//! for the char-placement research, so both algorithms can run over real
//! inference evidence without a device.
//!
//! Two modes:
//!   rec_dump <cases_dir> <out.jsonl>                 # vendored conformance corpus
//!   rec_dump --images <path|dir>... <out.jsonl>      # arbitrary screenshots/photos
//!
//! Arbitrary images carry no case JSON, so their rows carry an absolute
//! `image` path instead; real_eval prefers it when present.  Det parameters
//! are pinned to the app's shipped defaults (0.25 / 0.7, furigana off).

use std::io::Write;

use jpdict_core::models::RecognitionMode;
use jpdict_core::ocr_engine::{recognize_boxes_collect, OcrEngine};
use serde_json::Value;

fn dump_one(
    engine: &mut OcrEngine,
    image_path: &str,
    case_id: &str,
    extra: Value,
    out: &mut std::io::BufWriter<std::fs::File>,
) {
    let img = image::open(image_path).expect("image loads");
    let det = engine.detect_lines(&img).expect("detect");
    eprintln!("{case_id}: {} det boxes", det.boxes.len());
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
            "case": case_id,
            "i": idx,
            "bbox": [ann.bbox.x, ann.bbox.y, ann.bbox.w, ann.bbox.h],
            "quad": quad,
            "line": line,
            "image": extra,
        });
        writeln!(out, "{}", rec).expect("write");
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let out_path = &args[args.len() - 1];

    let assets = "/home/holopengin/repos/InstantJPDictDecky/accessibility_daemon/assets";
    let mut engine = OcrEngine::new(assets, RecognitionMode::Both, 4).expect("engine loads");
    let mut out = std::io::BufWriter::new(std::fs::File::create(out_path).expect("out file"));

    if args[1] == "--images" {
        // Arbitrary images: pinned app defaults, absolute image path per row.
        engine.det_thresh_override = Some(0.25);
        engine.det_unclip_override = Some(0.7);
        engine.det_furigana = false;
        let mut images: Vec<std::path::PathBuf> = Vec::new();
        for a in &args[2..args.len() - 1] {
            let p = std::path::PathBuf::from(a);
            if p.is_dir() {
                let mut got: Vec<_> = std::fs::read_dir(&p)
                    .expect("dir")
                    .filter_map(|e| e.ok())
                    .map(|e| e.path())
                    .filter(|q| {
                        matches!(
                            q.extension().and_then(|e| e.to_str()),
                            Some("png") | Some("jpg") | Some("jpeg")
                        )
                    })
                    .collect();
                got.sort();
                images.extend(got);
            } else {
                images.push(p);
            }
        }
        for path in &images {
            let name = path.file_stem().unwrap().to_string_lossy().into_owned();
            let abs = std::fs::canonicalize(path).expect("abs path");
            dump_one(&mut engine, &abs.display().to_string(), &name, Value::String(abs.display().to_string()), &mut out);
        }
    } else {
        // Vendored conformance corpus: case JSON decides det parameters.
        let cases_dir = std::path::PathBuf::from(&args[1]);
        let mut names: Vec<_> = std::fs::read_dir(&cases_dir)
            .expect("cases dir")
            .filter_map(|e| e.ok())
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| n.starts_with("recognition-") && n.ends_with(".json"))
            .collect();
        names.sort();
        for name in names {
            let text = std::fs::read_to_string(cases_dir.join(&name)).expect("case reads");
            let v: Value = serde_json::from_str(&text).expect("case parses");
            let c = &v["case"];
            let image_rel = c["image"].as_str().expect("image");
            let image_path = cases_dir.parent().unwrap().join(image_rel);
            engine.det_thresh_override = Some(c["det_thresh"].as_f64().unwrap_or(0.25) as f32);
            engine.det_unclip_override = Some(c["det_unclip"].as_f64().unwrap_or(0.7) as f32);
            engine.det_furigana = c["furigana_filter"].as_bool().unwrap_or(false);
            let case_id = v["id"].as_str().unwrap_or(&name).to_string();
            let abs = std::fs::canonicalize(&image_path).expect("abs path");
            dump_one(&mut engine, &abs.display().to_string(), &case_id, Value::String(abs.display().to_string()), &mut out);
        }
    }
    out.flush().unwrap();
    eprintln!("done -> {out_path}");
}
