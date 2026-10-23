// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Phrase

// Tests only. 1.1's l10n WP9 removed the String wrappers that gave core's words in English (`BrainModel.title`,
// `ModelLogic.refuse`…) from the code the app runs: everything on screen and aloud is a Phrase now. The tests' English
// assertions (1.0's words, byte for byte) read them through these one-liners, each the Phrase's `.en()`.

val AdbFix.title: String get() = titleText.en()
val AdbFix.why: String get() = whyText.en()
val AdbFixes.HEADER: String get() = headerText.en()
fun AdbFixes.label(fix: AdbFix, s: AdbFixState): String = labelText(fix, s).en()

val BargeIn.title: String get() = titleText.en()
val BargeIn.detail: String get() = detailText.en()
val Playback.title: String get() = titleText.en()
val Playback.detail: String get() = detailText.en()
val StopKey.title: String get() = titleText.en()
val Approval.title: String get() = titleText.en()
val SpeakReplies.title: String get() = titleText.en()
val VoiceChoice.title: String get() = titleText.en()
val VoiceChoice.detail: String get() = detailText.en()
val TextApp.title: String get() = titleText.en()
val NearestOnline.title: String get() = titleText.en()
val NearestOnline.detail: String get() = detailText.en()
val EmailApp.title: String get() = titleText.en()
val LicenceWhere.title: String get() = titleText.en()

fun BrainError.text(): String = textPhrase().en()
val AnthropicModel.detail: String get() = detailText.en()
val HelperEffortSetting.title: String get() = titleText.en()
fun HelperVoices.title(name: String): String = titleText(name).en()
val AskRoute.title: String get() = titleText.en()
val AskRoute.detail: String get() = detailText.en()
val Escalation.title: String get() = titleText.en()
val Escalation.detail: String get() = detailText.en()

val GpuFailure.what: String get() = titleText.en().removePrefix("The GPU ")
val TpuFailure.what: String get() = titleText.en().removePrefix("The TPU ")

val BrainModel.title: String get() = titleText.en()
val BrainModel.speed: String get() = speedText.en()
val BrainModel.about: String get() = aboutText.en()
val ModelTerms.Companion.INTRO: String get() = introText.en()
val ModelTerms.Companion.ALSO: String get() = alsoText.en()
val ModelTerms.Companion.REFUSED: String get() = refusedText.en()
fun Download.reasonText(status: Int, reason: Int): String = reasonPhrase(status, reason)?.en() ?: ""

fun ModelLogic.spaceProblem(
    m: BrainModel, freeBytes: Long, reclaimableBytes: Long, needBytes: Long = ModelFiles.needBytes(m), pendingBytes: Long = 0,
    fileBytes: Long = m.bytes, gguf: Boolean = false,
): String? = spaceProblemText(m, freeBytes, reclaimableBytes, needBytes, pendingBytes, fileBytes, gguf)?.en()
fun ModelLogic.name(m: BrainModel): String = nameText(m).en()
fun ModelLogic.refuseDownload(m: BrainModel, status: ModelStatus, freeBytes: Long, existingBytes: Long, pendingBytes: Long = 0, busyWith: BrainModel? = null): String? =
    refuseDownloadText(m, status, freeBytes, existingBytes, pendingBytes, busyWith)?.en()
fun ModelLogic.refuseImport(m: BrainModel, status: ModelStatus, docBytes: Long, freeBytes: Long, reclaimableBytes: Long, pendingBytes: Long = 0, busyWith: BrainModel? = null,
    gguf: Boolean = false): String? = refuseImportText(m, status, docBytes, freeBytes, reclaimableBytes, pendingBytes, busyWith, gguf)?.en()
fun ModelLogic.headline(status: ModelStatus, m: BrainModel = BrainModel.FAST): String = headlineText(status, m).en()
fun ModelLogic.downloadText(m: BrainModel, status: Int, reason: Int, soFar: Long): String = downloadPhrase(m, status, reason, soFar).en()
fun ModelLogic.gb(bytes: Long): String = gbText(bytes).en()

val LoadCheck.CHANGED: String get() = changedText.en()
val LoadCheck.NO_TPU: String get() = noTpuText.en()
val LoadCheck.NO_GPU: String get() = noGpuText.en()
fun LoadCheck.otherChip(built: Chip, phone: Chip): String = otherChipText(built, phone).en()
fun LoadCheck.wrongContext(expected: Int): String = wrongContextText(expected).en()
fun LoadCheck.refuse(m: BrainModel, backend: ModelBackend, fingerprintNow: String, mark: String?, ownRecord: String?,
    maxTokens: Int = ContextWindow.MAX_TOKENS, format: ModelFormat = ModelFormat.LITERT_LM, phone: Phone? = null): String? =
    refuseText(m, backend, fingerprintNow, mark, ownRecord, maxTokens, format, phone)?.en()
val ModelNotes.MEDICAL: String get() = medicalText.en()
fun ModelNotes.of(name: String?): String? = ofText(name)?.en()
val ModelChoice.NO_MODEL: String get() = noModelText.en()
val ModelChoice.STOPPING: String get() = stoppingText.en()
val ModelChoice.FAST_FAILED: String get() = fastFailedText.en()
fun ModelChoice.fallbackText(wanted: BrainModel, why: FallbackWhy, detail: String? = null): String =
    fallbackPhrase(wanted, why, detail?.let { Phrase.plain(it) }).en()

fun MtModelLogic.refuseDownload(b: MtBundle, status: ModelStatus, remaining: List<MtFile>, freeBytes: Long, staged: Set<String> = emptySet(), pendingBytes: Long = 0): String? =
    refuseDownloadText(b, status, remaining, freeBytes, staged, pendingBytes)?.en()
fun MtModelLogic.downloadText(b: MtBundle, doneBytes: Long, rows: List<FileDownload>): Pair<String, Int> =
    downloadPhrase(b, doneBytes, rows).let { (p, pct) -> p.en() to pct }

val Phone.name: String get() = nameText.en()
fun Phones.line(p: Phone): String = "This phone: " + phoneText(p).en()
fun Phones.tpuLine(p: Phone): String = tpuLineText(p).en()
val Phones.TPU_LIST: List<String> get() = tpuListText.map { it.en() }
val ModelCatalogue.FALLBACK_TITLE: String get() = fallbackTitleText.en()
fun ModelCatalogue.title(m: BrainModel, p: Phone, tpuFailed: Boolean = false): String = titleText(m, p, tpuFailed).en()
fun ModelCatalogue.memoryWarning(m: BrainModel, p: Phone): String? = memoryWarningText(m, p)?.en()
val SpeedEstimate.source: String get() = sourceText.en()
fun SpeedEstimate.text(): String = textPhrase().en()

val VoiceBundle.title: String get() = titleText.en()
val VoiceBundle.about: String get() = aboutText.en()
fun VoiceModelLogic.spaceProblem(b: VoiceBundle, remaining: List<VoiceFile>, freeBytes: Long, staged: Set<String> = emptySet(), pendingBytes: Long = 0): String? =
    spaceProblemText(b, remaining, freeBytes, staged, pendingBytes)?.en()
fun VoiceModelLogic.spaceProblem(title: String, need: Long, freeBytes: Long, pendingBytes: Long): String? =
    spaceProblemText(Phrase.plain(title), need, freeBytes, pendingBytes)?.en()
fun VoiceModelLogic.refuseDownload(
    b: VoiceBundle, status: ModelStatus, remaining: List<VoiceFile>, freeBytes: Long, staged: Set<String> = emptySet(), pendingBytes: Long = 0,
): String? = refuseDownloadText(b, status, remaining, freeBytes, staged, pendingBytes)?.en()
fun VoiceModelLogic.refuseDownload(status: ModelStatus, space: () -> String?): String? = refuseDownloadText(status) { space()?.let { Phrase.plain(it) } }?.en()
fun VoiceModelLogic.headline(status: ModelStatus): String = headlineText(status).en()
fun VoiceModelLogic.downloadText(b: VoiceBundle, doneBytes: Long, rows: List<FileDownload>): Pair<String, Int> = downloadText(b.bytes, doneBytes, rows)
fun VoiceModelLogic.downloadText(total: Long, doneBytes: Long, rows: List<FileDownload>): Pair<String, Int> =
    downloadPhrase(total, doneBytes, rows).let { (p, pct) -> p.en() to pct }
fun VoiceModelLogic.installText(percent: Int): String = installPhrase(percent).en()
fun VoiceModelLogic.size(bytes: Long): String = sizeText(bytes).en()
fun EngineState.text(): String = textPhrase().en()
fun VoiceBenchLogic.lines(b: VoiceBench): List<String> = linesText(b).map { it.en() }

fun DownloadPolicy.title(bytes: Long): String = titleText(bytes).en()
fun DownloadPolicy.text(bytes: Long): String = textPhrase(bytes).en()
val DownloadPolicy.WAIT: String get() = waitText.en()
val DownloadPolicy.ANYWAY: String get() = anywayText.en()
