# Bundled data and its source

`ASSET-LICENSES.json` records the exact hash, license and notice for every
bundled data file. License texts and attribution notices are packaged under
`app/src/main/assets/licenses/`. App code is Apache-2.0; the data keeps its
separate terms.

| Data | Source and generation material |
| --- | --- |
| Chime weights and English frequency lists | The [Chime model](https://huggingface.co/lukeslp/chime-sms-2m) and [public pipeline](https://github.com/lukeslp/chime-pipeline) document the export, licenses, inputs and historical reproduction limits. The app includes the pure Kotlin reader and inference code. |
| English, Spanish and Portuguese corpus priors | Numeric LTCP statistics. Notices identify the corpus sources. `model/prior.py`, `model/tokenize.py` and `model/language_build.py` provide the generation code. Spanish and Portuguese use the recorded Leipzig corpora. |
| Spanish and Portuguese word lists | FrequencyWords/OpenSubtitles, with the source revision in the asset inventory. `model/language_bundle.py` and its included helpers generate these lists. |
| Hebrew word lists | Pinned FrequencyWords/OpenSubtitles input; `model/hebrew_lists.py`. |
| Welsh, Irish and Māori word lists | The November 1, 2026 Wikipedia dumps named in the inventory and asset headers; `model/small_language_lists.py`. |
| Hawaiian word lists | The Hawaiian corpus source named in the inventory, with CC0 terms; `model/small_language_lists.py`. |
| British spelling pairs | VarCon 2020.12.07, with its input digest and full notice; `model/british_spellings.py`. |
| Emoji names and inventory | Unicode and the separately licensed Chime vocabulary. `scripts/build-emoji-inventory.py` generates the Unicode inventory. |
| Top-level domains | The included MPL-2.0 Public Suffix List revision and compatibility exclusions; `scripts/build-tlds.py --check` verifies the packaged list. |
| Swipe weights | First-party LTSW numeric weights, with the recording-source and derivative notices retained. The app includes its pure Kotlin reader and inference code. The format audit checks the exact payload; it does not rerun training. |
| Reviewed vocabulary and known-word lists | The asset inventory and word-list notices distinguish original editorial rows, SCOWL material and FrequencyWords derivatives. |

The generation tools are here for inspection and need separately supplied
source data. The Android build doesn't run them, and some use NumPy. They don't
download a corpus, and they don't make the historical model training fully
reproducible. Reproducing the APK is a separate check, described in
`CONTRIBUTING.md`.

F-Droid's recipe exempts five exact data files from its binary scan: the Chime
weights, the three corpus priors and the swipe weights. The audit checks their
hashes, documented layouts and, where present, checksums. No native library or
directory is exempted, and F-Droid's maintainers still have to review how these
files are treated.
