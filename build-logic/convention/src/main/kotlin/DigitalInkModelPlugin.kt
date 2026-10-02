/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * custom: 构建期拉取「谷歌数字墨水」模型到 assets（随包内置，免运行时下载）。
 *
 * 模型不随 ML Kit SDK 分发。ML Kit digital-ink AAR 内的 `assets/manifest.json` 列出了每个
 * pack 的 zip 直链与 md5，`assets/packmapping.pb` 给出语言 tag → pack 的映射；本任务按
 * [DIGITAL_INK_ZH_HANI_PACKS]（中文文字模型）与 [DIGITAL_INK_GESTURE_PACKS]（全部手势
 * 分类器）下载、校验 md5、解压到
 * `<buildDir>/generated/digitalink-model/assets/mlkit/digitalink/...`，其目录结构就是
 * ML Kit/GMS MDD 在应用私有目录里的布局（另见 `MlKitBundledModel`）。
 *
 * 说明：MDD 的「文件组已下载」状态（`shared_prefs/gms_icing_mdd_*` 等）官方不提供下载，
 * 由仓库内 `app/src/main/mlkit-assets/` 随源码携带，与这里的产物合并成同一 assets 树。
 *
 * 离线构建可用 `-PdigitalInkModel=off` 跳过拉取（包内将不含内置模型：引擎链会回落到
 * 系统内置引擎，或由用户在设置页手动下载）。
 */

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * `zh-Hani` 的 pack 定义：`pack 名 -> "zip 直链|md5|目标目录（相对 assets 根）"`。
 *
 * - 直链与 md5 取自 ML Kit digital-ink AAR 的 `assets/manifest.json`（`packs[].download_urls`
 *   与 `md5_checksum`）；升 ML Kit 版本后如报 md5 不匹配，照该文件更新即可；
 * - 目标目录名（`datadownloadfile_<timestamp>`）被仓库内 MDD 状态 XML 索引引用，
 *   **改名会让 MDD 找不到文件**，故与状态文件一起固定。
 */
private val DIGITAL_INK_ZH_HANI_PACKS = linkedMapOf(
    "lstm_chinese_tflite_4_192_tflite_20181109_zip" to
            "https://dl.google.com/handwriting/models/lstm.chinese.tflite_4_192.tflite.20181109.zip" +
            "|b4e30f63c66538e59a62a4fc4c2bbdff" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497352",
    "qrnn_zh_reco_20191217_fst_none_recospec_zip" to
            "https://dl.google.com/handwriting/models/qrnn.zh.reco_20191217.fst_none.recospec.zip" +
            "|2dec4a29db406f066460ffdf46ba62bc" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497651",
)

/**
 * **手势分类器**（`<语言 tag>-x-gesture`）的全部 pack：`pack 名 -> "zip 直链|md5|目标目录"`。
 *
 * ML Kit 的手势分类不是独立 API：它是 `<tag>-x-gesture` 的**另一个模型**，输出候选的 `text`
 * 是手势类名（`scribble`/`circle`/`caret:above`…，见 `GoogleGestureLabels`）。故「谷歌手势
 * 识别器可用」= 该模型已就绪，与文字模型一样只能靠下载 —— 这里全部随包内置，于是
 * **非小米设备在离线状态下也能用真正的模型判手势**（而非仅本地几何启发式）。
 *
 * 覆盖面 = 官方 `packmapping.pb` 里被任一 `-x-gesture` tag 引用的 27 种文字（54 个 pack，
 * 压缩后约 1.06MB）；数据与 md5 取自 ML Kit AAR 的 `assets/manifest.json`，目标目录取自
 * 仓库内 MDD 状态（`shared_files` 的 sha1→目录索引）——三者必须自洽，改名会让 MDD 找不到文件。
 */
private val DIGITAL_INK_GESTURE_PACKS = linkedMapOf(
    "scribe_arabic_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.arabic.20221129tfreco.recospec.zip" +
            "|eac6604ea02a00b09f68532fe46eada2" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497268",
    "scribe_arabic_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.arabic.20221129tfreco.tflite.zip" +
            "|19fbf6f19c3fed7da54c0c48d73b6145" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497269",
    "scribe_armenian_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.armenian.20221129tfreco.recospec.zip" +
            "|a0000a0c0708c18fa4b8e160a552399a" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497240",
    "scribe_armenian_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.armenian.20221129tfreco.tflite.zip" +
            "|3c3dc41d0cd698773309cdf6930fcc43" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497241",
    "scribe_bengali_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.bengali.20221129tfreco.recospec.zip" +
            "|bcd620c082483b4b7116c8531fe5df0b" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497442",
    "scribe_bengali_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.bengali.20221129tfreco.tflite.zip" +
            "|225523cd4d950c28a620397108b0f384" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497443",
    "scribe_chinese_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.chinese.20221129tfreco.recospec.zip" +
            "|d8783bf2f59c6e19ff6a2584036ab7f3" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497683",
    "scribe_chinese_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.chinese.20221129tfreco.tflite.zip" +
            "|2554de5580c3d9f5a00c0c2f59b0665c" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497684",
    "scribe_cyrillic_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.cyrillic.20221129tfreco.recospec.zip" +
            "|e0ea405fc3dd82d723b9c2d492ed700b" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497230",
    "scribe_cyrillic_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.cyrillic.20221129tfreco.tflite.zip" +
            "|3764f4b84c6e9599d10e4c3bcd5c95f5" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497231",
    "scribe_devanagari_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.devanagari.20221129tfreco.recospec.zip" +
            "|b8e9e440017c5d7141055e3356855217" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497162",
    "scribe_devanagari_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.devanagari.20221129tfreco.tflite.zip" +
            "|5a2f444400ad4ee9e49e6f071bf60aa9" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497163",
    "scribe_ethiopic_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.ethiopic.20221129tfreco.recospec.zip" +
            "|8d621e844c284c977e5acd9328809c5a" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497672",
    "scribe_ethiopic_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.ethiopic.20221129tfreco.tflite.zip" +
            "|fea0e19a92a8b901487ce136f0b36b06" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497673",
    "scribe_georgian_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.georgian.20221129tfreco.recospec.zip" +
            "|553bfda686603241615038efa10457a4" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497207",
    "scribe_georgian_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.georgian.20221129tfreco.tflite.zip" +
            "|230f90606b938c70a9fdf9c90e5c31db" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497208",
    "scribe_greek_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.greek.20221129tfreco.recospec.zip" +
            "|1c25a05b2592be9761a9a9936b3de70a" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497950",
    "scribe_greek_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.greek.20221129tfreco.tflite.zip" +
            "|d99f4a2c7616503f73074f2074e43509" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497951",
    "scribe_gujarati_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.gujarati.20221129tfreco.recospec.zip" +
            "|4d67a4af42a05f78ddc52f97c0e6cf16" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497773",
    "scribe_gujarati_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.gujarati.20221129tfreco.tflite.zip" +
            "|7c5b7095376cf44140087c04d63575f3" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497774",
    "scribe_hebrew_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.hebrew.20221129tfreco.recospec.zip" +
            "|146866d2b5ae12b0f7b2eb91c415d4c5" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497469",
    "scribe_hebrew_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.hebrew.20221129tfreco.tflite.zip" +
            "|4b831da3d8133ae42a6debe41f6f3657" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497470",
    "scribe_japanese_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.japanese.20221129tfreco.recospec.zip" +
            "|bd10fd013c375f7cb3c62ea13967b25d" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497734",
    "scribe_japanese_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.japanese.20221129tfreco.tflite.zip" +
            "|ab94f61953b2fba2aa86566faf32bb2e" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497735",
    "scribe_kannada_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.kannada.20221129tfreco.recospec.zip" +
            "|acffec60c14f51d302a4d006c4bf20a0" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497535",
    "scribe_kannada_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.kannada.20221129tfreco.tflite.zip" +
            "|6454cf6ca6365eb3d6ebfa4bcb2a367f" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497536",
    "scribe_khmer_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.khmer.20221129tfreco.recospec.zip" +
            "|e05d802c46350b95d2e96ac4f09308e4" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497622",
    "scribe_khmer_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.khmer.20221129tfreco.tflite.zip" +
            "|6e722dcd28add3be1bea4b619ac1050c" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497623",
    "scribe_korean_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.korean.20221129tfreco.recospec.zip" +
            "|293dede89dbbbc87ebb865a17cc819d7" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497668",
    "scribe_korean_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.korean.20221129tfreco.tflite.zip" +
            "|054ca3bb2f3fbc23940b9f8b3f37e32e" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497669",
    "scribe_lao_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.lao.20221129tfreco.recospec.zip" +
            "|ee1c26703cb619167a15237baa13a828" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497175",
    "scribe_lao_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.lao.20221129tfreco.tflite.zip" +
            "|911299cbad3375a3bdace474c68a549a" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497176",
    "scribe_latin_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.latin.20221129tfreco.recospec.zip" +
            "|fe23b267e63a59c91bc7132f86565659" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497164",
    "scribe_latin_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.latin.20221129tfreco.tflite.zip" +
            "|775f130afeb3ed12ac530feb7449149c" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497165",
    "scribe_malayalam_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.malayalam.20221129tfreco.recospec.zip" +
            "|2b03d62f662a4b2788227d4f1964c06e" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497252",
    "scribe_malayalam_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.malayalam.20221129tfreco.tflite.zip" +
            "|57774b90595976281662ac2b3ba0dce3" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497253",
    "scribe_myanmar_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.myanmar.20221129tfreco.recospec.zip" +
            "|907478aef286a35b9d00f3f02de9ec99" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497938",
    "scribe_myanmar_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.myanmar.20221129tfreco.tflite.zip" +
            "|ce6f467f0ca92393875cba67bd0aaf81" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497939",
    "scribe_odia_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.odia.20221129tfreco.recospec.zip" +
            "|8cdda05885de7d67e051df0b78d4adf0" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497886",
    "scribe_odia_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.odia.20221129tfreco.tflite.zip" +
            "|5f08eea54d3284b2f610b75ebfd928e3" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497887",
    "scribe_punjabi_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.punjabi.20221129tfreco.recospec.zip" +
            "|bc0b0f15e296c3736b656d2010cf16cc" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497870",
    "scribe_punjabi_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.punjabi.20221129tfreco.tflite.zip" +
            "|78e351c126cc5a61b13d3ad69953d2d8" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497871",
    "scribe_sinhala_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.sinhala.20221129tfreco.recospec.zip" +
            "|326f40e0960e2733faf9901430c30001" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497896",
    "scribe_sinhala_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.sinhala.20221129tfreco.tflite.zip" +
            "|2208d029a9a1e9cf344c4af84b73d2bf" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497897",
    "scribe_tamil_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.tamil.20221129tfreco.recospec.zip" +
            "|e8e103e10c70f9ca1dff1d978eb55aff" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497914",
    "scribe_tamil_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.tamil.20221129tfreco.tflite.zip" +
            "|28fa1d7355d1fa28eb2fe7b88e556a43" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497915",
    "scribe_telugu_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.telugu.20221129tfreco.recospec.zip" +
            "|c80d8ef39ee0aafc0b94aaeaa134e3f8" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497456",
    "scribe_telugu_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.telugu.20221129tfreco.tflite.zip" +
            "|a58dc8f56979d3238724dddac3ab1d5a" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497457",
    "scribe_thai_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.thai.20221129tfreco.recospec.zip" +
            "|24ad75224e63b07150735e5bd8ff0c44" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497302",
    "scribe_thai_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.thai.20221129tfreco.tflite.zip" +
            "|e8efd6211d7ad436fe14ab1fc66ee90b" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497303",
    "scribe_tibetan_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.tibetan.20221129tfreco.recospec.zip" +
            "|d7c644e9479b47376358f0f5835be24c" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497603",
    "scribe_tibetan_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.tibetan.20221129tfreco.tflite.zip" +
            "|a19ecd1dbd579c6eb6e3c84a047b0909" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497604",
    "scribe_vietnamese_20221129tfreco_recospec_zip" to
            "https://dl.google.com/handwriting/models/scribe.vietnamese.20221129tfreco.recospec.zip" +
            "|e63d4b7fd90a5669071885aef5b2087e" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497746",
    "scribe_vietnamese_20221129tfreco_tflite_zip" to
            "https://dl.google.com/handwriting/models/scribe.vietnamese.20221129tfreco.tflite.zip" +
            "|912e0d95cd0d0f326fd9469f4d2405c4" +
            "|files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_1790875497747",
)

abstract class FetchDigitalInkModelTask : DefaultTask() {

    /** pack 名 → `"url|md5|目标目录"`。 */
    @get:Input
    abstract val packs: MapProperty<String, String>

    /** `-PdigitalInkModel=off`：跳过拉取（不产出内置模型）。 */
    @get:Input
    abstract val skip: Property<Boolean>

    /** assets 源集根（其下为 `mlkit/digitalink/...`）。 */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        root.mkdirs()
        if (skip.get()) {
            logger.lifecycle("跳过谷歌数字墨水模型拉取（-PdigitalInkModel=off）：包内不含内置模型")
            return
        }
        packs.get().forEach { (pack, spec) -> fetchPack(root, pack, spec) }
    }

    private fun fetchPack(assetsRoot: java.io.File, pack: String, spec: String) {
        val parts = spec.split('|')
        require(parts.size == 3) { "pack '$pack' 定义格式应为 url|md5|目标目录" }
        val (url, expectedMd5, targetDir) = parts
        val target = assetsRoot.resolve(DigitalInkModelPaths.ASSET_ROOT).resolve(targetDir)
        target.mkdirs()

        val zipBytes = download(url)
        val actualMd5 = md5(zipBytes)
        check(actualMd5.equals(expectedMd5, ignoreCase = true)) {
            "$pack 的 md5 不匹配：期望 $expectedMd5，实际 $actualMd5（$url）"
        }
        var written = 0
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                if (name.isEmpty()) continue
                target.resolve(name).outputStream().buffered().use { zip.copyTo(it) }
                written++
            }
        }
        check(written > 0) { "$pack 解压后没有任何文件（$url）" }
        logger.lifecycle("拉取 $pack -> ${target.relativeTo(assetsRoot)}（$written 个文件）")
    }

    private fun download(url: String): ByteArray {
        val connection = try {
            (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 300_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "fcitx5-android-build")
            }
        } catch (t: Throwable) {
            throw IllegalStateException(buildFailureHint(url), t)
        }
        return try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                buildFailureHint(url) + "（HTTP ${connection.responseCode}）"
            }
            connection.inputStream.use { it.readBytes() }
        } catch (t: Throwable) {
            throw IllegalStateException(buildFailureHint(url), t)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildFailureHint(url: String): String =
        "无法拉取谷歌数字墨水模型：$url\n" +
                "  · 需要能访问 dl.google.com 的网络；若本次构建不需要内置模型，用 -PdigitalInkModel=off 跳过"

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

/**
 * 注册构建期任务 `fetchDigitalInkModel`。
 *
 * 消费方（`app`）：
 * - `android.sourceSets.main.assets.srcDir(<buildDir>/generated/digitalink-model/assets)`
 * - `merge*Assets` 显式 `dependsOn("fetchDigitalInkModel")`
 */
class DigitalInkModelPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val generated = target.layout.buildDirectory.dir(DigitalInkModelPaths.GENERATED_DIR)
        target.tasks.register(
            "fetchDigitalInkModel",
            FetchDigitalInkModelTask::class.java
        ) {
            group = "custom"
            description = "构建期拉取谷歌数字墨水中文文字模型与全部手势分类器到 assets（md5 按 ML Kit 官方 manifest 校验）"
            // 文字模型（zh-Hani）+ 全部 `-x-gesture` 手势分类器：两组的 pack 名互不重叠
            packs.set(DIGITAL_INK_ZH_HANI_PACKS + DIGITAL_INK_GESTURE_PACKS)
            skip.set(
                target.providers.gradleProperty("digitalInkModel")
                    .map { it.equals("off", ignoreCase = true) || it.equals("skip", ignoreCase = true) }
                    .orElse(false)
            )
            outputDir.set(generated.map { it.dir(DigitalInkModelPaths.ASSETS_SUBDIR) })
        }
    }
}

/**
 * 产物路径常量：插件与 app 脚本共用，避免把 Provider 暴露出去。
 */
object DigitalInkModelPaths {

    /** 相对 app 模块 `buildDir` 的产物目录。 */
    const val GENERATED_DIR = "generated/digitalink-model"

    /** 产物内作为 assets 源集根的子目录。 */
    const val ASSETS_SUBDIR = "assets"

    /** 内置模型在 assets 内的根路径（与 `MlKitBundledModel.ASSET_ROOT` 一致）。 */
    const val ASSET_ROOT = "mlkit/digitalink"
}
