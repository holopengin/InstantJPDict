# <img src="./logo.svg"> InstantJPDict

InstantJPDict is an Android application which provides instant Japanese-to-English dictionary lookups using on-device OCR (Optical Character Recognition). It allows users to capture text from their screen and get immediate definitions, making it ideal for reading games, browsing social media, or any other activity where copying text is difficult.

Currently only tested with [JMDict and KANJIDIC for Yomitan](https://github.com/yomidevs/jmdict-yomitan).

## Demo
<div><video controls src="https://github.com/user-attachments/assets/06a01786-9082-4fea-a1fb-0a463180bd99"></video></div>

## Features
- **On-device OCR**: High-speed Japanese text recognition without needing an internet connection.
- **Instant Lookup**: Tap recognized characters to see dictionary entries immediately.
- **Deinflection**: Support for verb and adjective conjugations, just like yomitan.
- **Floating Overlay**: Accessible from any app via an accessibility service.
- **Frictionless Corrections**: In the rare case the OCR makes a mistake, corrections are only a tap away. We use the text recognition model's own prediction ratings to provide the most likely alternatives, as well as a manual input mode.
- **Yomitan Dictionaries**: Ingests Yomitan format dictionaries such as https://github.com/yomidevs/jmdict-yomitan
- **Dictionary catalog**: Browse popular dictionaries (JMdict, KANJIDIC, and the bundled pitch-accent set) and import one in a tap — the app downloads it, checks it against a pinned SHA-256, and adds it, no file picker needed. This is the only feature that uses the network, and only when you tap Import.

## Roadmap
- [ ] Train a better model for vertical text recognition
- [x] Camera mode (#78)
- [ ] Train a spline-based text line detection model for good camera OCR and weird text

## Credits
The shipped OCR models are the **PP-OCRv6 small** detection and recognition pair from
**PaddleOCR**:
- [PP-OCRv6_small_det_onnx (Hugging Face)](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx)
- [PP-OCRv6_small_rec_safetensors (Hugging Face)](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_safetensors)

This project was originally prototyped against the **MeikiOCR** models — the
[repository](https://github.com/rtr46/meikiocr), `meiki.text.detect.v0` and
`meiki.txt.recognition.v0`. Those baselines are deprecated and no longer stored
in this repository; the PP-OCRv6 pair above supersedes them.

This project was also heavily inspired by the **Yomitan** hover dictionary, uses its rule files,
and ingests its dictionary format:
- [Yomitan Repository](https://github.com/yomidevs/yomitan)


## License
This project is licensed under the AGPL-v3 License. See the [LICENSE](LICENSE) file for details.
If you would like to use this code under a different license, please contact me. As this is a
learning tool I developed solely for myself and my friends, I would like to keep the project as free
and open as possible for individuals to use and hack on.
