# <img src="./logo.svg"> InstantJPDict

InstantJPDict is an Android application which provides instant Japanese-to-English dictionary lookups using on-device OCR (Optical Character Recognition) pipeline. Ideal for reading games, social media, or any other Japanese content where copying text is difficult. Also includes an experimental camera mode so you can do lookups faster than web-based tools ever could.

It imports dictionaries in the Yomitan format such as [Jitendex, ](https://jitendex.org/pages/downloads.html)[JMDict, and KANJIDIC](https://github.com/yomidevs/jmdict-yomitan). Currently untested with other dictionaries.

## Demo
<div><video controls src="https://github.com/user-attachments/assets/06a01786-9082-4fea-a1fb-0a463180bd99"></video></div>

## Features
- **On-device OCR**: High-speed Japanese text recognition without an internet connection.
- **Floating Overlay**: Tap the button to scan your screen, anywhere.
- **Camera Mode**: Instant OCR for your physical books, manga, games, etc.
- **Yomitan Dictionaries**: Imports Yomitan format dictionaries, with one-click installs for a curated selection.
- **Instant Lookup**: Tap recognized characters to see dictionary entries immediately.
- **Deinflection**: Support for verb and adjective conjugations, just like yomitan. Tap the first character of a word, and the dictionary form will surface.
- **Frictionless Corrections**: In the rare case the OCR makes a mistake, corrections are only a tap away. We use the text recognition model's own prediction ratings, plus a lightweight n-gram model and kanji decomposition table, to provide the most likely alternatives. Plus a manual input mode as a last resort.

## Roadmap
- [ ] Standard dictionary search mode + share text from other apps
- [ ] Better scene-text detection for camera mode (keystone, curves, skew)

## Credits
The shipped OCR models are the **PP-OCRv6 small** detection and recognition pair from
**PaddleOCR**:
- [PP-OCRv6_small_det_onnx (Hugging Face)](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx)
- [PP-OCRv6_small_rec_safetensors (Hugging Face)](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_safetensors)

This project was also heavily inspired by the **Yomitan** hover dictionary, uses its rule files,
and ingests its dictionary format:
- [Yomitan Repository](https://github.com/yomidevs/yomitan)


## License
This project is licensed under the AGPL-v3 License. See the [LICENSE](LICENSE) file for details.
If you would like to use this code under a different license, please contact me. As this is a
learning tool I developed solely for myself and my friends, I would like to keep the project as free
and open as possible for individuals to use and hack on.
