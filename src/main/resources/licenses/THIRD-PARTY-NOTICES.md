# Third-party components

Project source code retains its own license. The following components retain their upstream licenses.

## all-MiniLM-L6-v2

- Publisher: sentence-transformers
- License: Apache License 2.0 (see Apache-2.0.txt)
- Source: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
- Bundled ONNX source: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/blob/main/onnx/model.onnx
- ONNX SHA-256: 6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452
- Vocabulary SHA-256: 07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3

The model and vocabulary are not tracked in this project's Git repository. Releases built with installed model resources include these unmodified files. The model license does not change the license of project source code.

## Java Native Access (JNA) 5.17.0

- Source: https://github.com/java-native-access/jna
- Copyright: JNA contributors
- License selected for this distribution: Apache License 2.0 (see Apache-2.0.txt)
- Original license and notices remain in the dependency JAR.

Other runtime dependencies retain their license and attribution files in their distributed JARs.

## TwelveMonkeys ImageIO 3.15.3

- Source: https://github.com/haraldk/TwelveMonkeys
- Copyright: Harald Kuhr and TwelveMonkeys contributors
- License: BSD 3-Clause; original license and notices remain in the dependency JARs.
- The WebP reader decodes locally cached website illustrations. Display assets are saved as PNG for JavaFX compatibility.

## Apache POI 6.0.0 and Apache PDFBox 3.0.8

- Sources: https://poi.apache.org/ and https://pdfbox.apache.org/
- License: Apache License 2.0 (see Apache-2.0.txt). Original license and notices remain in dependency JARs.
- POI reads Word DOC/DOCX bodies, tables and embedded images. PDFBox reads PDF pages and renders scan or layout previews locally.
- Windows OCR uses Windows.Media.Ocr provided by the installed operating system; no OCR model weights are bundled.
