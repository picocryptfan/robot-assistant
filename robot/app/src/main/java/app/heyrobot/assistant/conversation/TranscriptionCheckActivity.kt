package app.heyrobot.assistant.conversation

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.heyrobot.BuildConfig
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.car.CarModeController
import app.heyrobot.assistant.handsfree.HandsFreeHub
import app.heyrobot.core.CheckClips
import app.heyrobot.core.CheckLine
import app.heyrobot.core.CheckMic
import app.heyrobot.core.CheckMics
import app.heyrobot.core.CheckMode
import app.heyrobot.core.CheckReport
import app.heyrobot.core.CheckSentences
import app.heyrobot.core.CheckVerdict
import app.heyrobot.core.LangLabel
import app.heyrobot.core.Pcm
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.headset.CaptureSource
import app.heyrobot.core.headset.RoutePlan
import app.heyrobot.core.headset.RoutePlanner
import app.heyrobot.core.output.RouteType
import app.heyrobot.sys.AudioDevices
import app.heyrobot.sys.HandsFreeFiles
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.TickRow
import app.heyrobot.ui.theme.RobotTheme
import app.heyrobot.voice.AudioFrames
import app.heyrobot.voice.CaptureRoute
import app.heyrobot.voice.ConversationEngine
import app.heyrobot.voice.Listener
import app.heyrobot.voice.ListenerConfig
import app.heyrobot.voice.SessionRoute
import app.heyrobot.voice.VoiceEngines
import java.util.concurrent.Executors

// VOICE PROCESS. Settings › Voice › Transcription check (docs/conversation-mode.md 2.5, phase 5.0(a)): the Québec-French
// gate, on the phone. Read the sentences (20 Québec French, 10 English), or say anything and correct what Robot wrote,
// or import clips with a .txt of their lines; Robot decodes each recording with conversation mode's settings (the same
// voice detector, 0.25 s segments and Parakeet) and shows the word error rate per line and in all, the language it
// heard and the decode time, against the pass marks (15 % for Québec French, 8 % for English). Recordings stay in
// memory and are dropped once scored; the voice log gets numbers only (event 117); "Copy report" copies numbers unless
// "Include sentences" is ticked. In this process because it uses the speech model (the main app never loads one).
// 0.8: "Microphone: Phone / Headset" while a headset (or car mode's car) with a microphone is connected: the headset's
// microphone is routed as a headset or car session routes it (core CheckMics, RoutePlanner: the call route with
// setCommunicationDevice and the capture pinned to its input), recorded only once its input is the one capturing, and let
// go when the recording stops; its lines are scored apart from the phone's.

class TranscriptionCheckActivity : ComponentActivity() {
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-check") }

    /** Kept for the process (a rotation keeps the lines); never written anywhere. */
    private object Results {
        val lines = mutableStateListOf<CheckLine>()
        var logged = 0
    }

    // The recording: frames from the shared listener, or a capture of its own.
    private val recLock = Any()
    private var recorded = ArrayList<FloatArray>()
    private var recSamples = 0
    private var source: AudioFrames? = null
    private var own: Listener? = null
    private val level = mutableStateOf(-120f)
    private val sink: (FloatArray) -> Unit = { x ->
        synchronized(recLock) {
            if (recSamples < MAX_SAMPLES) {
                recorded += x.copyOf()
                recSamples += x.size
            }
        }
        var ss = 0.0
        for (v in x) ss += v * v
        level.value = Listener.db(ss / x.size.coerceAtLeast(1)).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        VoiceHub.init(this)
        setContent { RobotTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        refreshHeadset()
    }

    override fun onPause() {
        super.onPause()
        if (recording.value) stopRecording()
        if (isFinishing) logResults()
    }

    // ------------------------------------------------------------------ 0.8: the headset's microphone

    /** Microphone: the phone's, or the headset's ([headset]'s route). */
    private val mic = mutableStateOf(CheckMic.PHONE)
    /** The headset route on offer now (core CheckMics), or null: none with a microphone is connected. */
    private val headset = mutableStateOf<RoutePlan?>(null)
    /** The microphone the recording under way (or the last one) came from: its lines say so. */
    private var recMic = CheckMic.PHONE
    private val routeOwner = Any()
    private var routeHeld = false
    private val main = Handler(Looper.getMainLooper())
    private var connecting: Runnable? = null

    private fun headsetRoute(): RoutePlan? = try {
        CheckMics.route(AudioDevices.all(this), HandsFreeFiles.store(this).devices(), CarModeController.carSeen())
    } catch (t: Throwable) {
        RLog.w("check_headset", t)
        null
    }

    private fun refreshHeadset() {
        headset.value = headsetRoute()
        if (headset.value == null && !recording.value) mic.value = CheckMic.PHONE
    }

    /** The input of [plan]'s headset (AudioRecord.setPreferredDevice), as a headset session pins it. */
    private fun headsetInput(plan: RoutePlan): AudioDeviceInfo? = try {
        val want = plan.input
        val near = plan.communication ?: plan.output
        if (want == null) null else getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { d ->
            RouteType.of(d.type) == want && (near == null || !want.bluetooth || AudioDevices.seen(this, d).samePhysical(near))
        }
    } catch (_: Throwable) { null }

    /** [plan]'s route is up: the call route on its headset (Bluetooth), and the capture coming from its input. */
    private fun routedTo(plan: RoutePlan): Boolean {
        val comm = plan.communication?.let { c ->
            try { getSystemService(AudioManager::class.java).communicationDevice?.let { AudioDevices.seen(this, it).samePhysical(c) } == true } catch (_: Throwable) { false }
        } ?: true
        val input = (source as? Listener)?.routedInput?.let { RouteType.of(it.type) }
        return comm && input != null && input == plan.input
    }

    /** Takes the headset's route as a session does; false (with why) when it can't. */
    private fun takeHeadset(plan: RoutePlan): Boolean {
        // A headset or car session holds its own route; a call's audio is never touched.
        if (HandsFreeHub.session != null) { problem.value = "A headset or car session is on: end it first."; return false }
        if (HandsFreeHub.callActive()) { problem.value = "A call is on: its audio stays as it is."; return false }
        plan.communication?.let { c ->
            val out = AudioDevices.info(this, c, outputs = true)
            if (out == null || !SessionRoute.take(this, routeOwner, out)) { problem.value = "Android didn't give Robot the headset's microphone."; return false }
        }
        routeHeld = true
        CaptureRoute.set(plan.capture == CaptureSource.VOICE_COMMUNICATION, headsetInput(plan))
        return true
    }

    /** The headset's route goes: the phone's microphone and normal audio again. */
    private fun releaseHeadset() {
        connecting?.let { main.removeCallbacks(it) }
        connecting = null
        if (!routeHeld) return
        routeHeld = false
        SessionRoute.release(this, routeOwner)
        CaptureRoute.clear()
    }

    override fun onDestroy() {
        work.shutdown()
        super.onDestroy()
    }

    /** Event 117 for each language, once per new result set. Numbers only. */
    private fun logResults() {
        if (Results.lines.size == Results.logged) return
        Results.logged = Results.lines.size
        for ((wer, n, lang) in CheckReport.logEvents(Results.lines.toList())) VoiceLog.add(VoiceEvent.CONV_CHECK, wer, n, lang.id)
    }

    private val recording = mutableStateOf(false)
    private val busy = mutableStateOf<String?>(null)
    private val problem = mutableStateOf<String?>(null)

    private fun startRecording(): Boolean {
        if (ConversationSession.running) {
            problem.value = "A transcription is running. Stop it first."
            return false
        }
        synchronized(recLock) { recorded = ArrayList(); recSamples = 0 }
        // 0.8: the headset's microphone: its route first, then the capture (the shared listener reopens on it).
        val plan = if (mic.value == CheckMic.HEADSET) headsetRoute() ?: run {
            problem.value = "No headset with a microphone is connected."
            refreshHeadset()
            return false
        } else null
        if (plan != null && !takeHeadset(plan)) return false
        recMic = if (plan != null) CheckMic.HEADSET else CheckMic.PHONE
        val hub = VoiceHub.listener?.takeIf { it.running }
        if (hub != null) {
            source = hub
        } else {
            val l = Listener(this, ListenerConfig(wakeWord = false), null, onWake = { _, _ -> })
            if (!l.start()) {
                l.stop()
                releaseHeadset()
                problem.value = "Robot can't use the microphone (allow it in the Robot app, or close the app that is recording)."
                return false
            }
            own = l
            source = l
        }
        if (plan == null) source?.addSink(sink) else waitForHeadset(plan)
        problem.value = null
        recording.value = true
        // Nobody's "hey robot" opens the panel mid-sentence ("stop" still works while Robot talks).
        VoiceHub.conversationWake(true)
        return true
    }

    /** Records once the headset's input is the one capturing (a Bluetooth route takes a second or two); else says so. */
    private fun waitForHeadset(plan: RoutePlan) {
        busy.value = "Connecting the headset's microphone…"
        val started = SystemClock.elapsedRealtime()
        val check = object : Runnable {
            override fun run() {
                if (connecting !== this || !recording.value) return
                if (routedTo(plan)) {
                    connecting = null
                    busy.value = null
                    synchronized(recLock) { recorded = ArrayList(); recSamples = 0 }
                    source?.addSink(sink)
                    return
                }
                if (SystemClock.elapsedRealtime() - started >= RoutePlanner.ROUTE_TIMEOUT_MS) {
                    connecting = null
                    busy.value = null
                    stopRecording()
                    problem.value = "The headset's microphone didn't connect. Try again, or use the phone's."
                    return
                }
                main.postDelayed(this, 100)
            }
        }
        connecting = check
        main.postDelayed(check, 100)
    }

    private fun stopRecording(): FloatArray {
        source?.removeSink(sink)
        source = null
        own?.let { l -> Thread({ l.stop() }, "robot-check-mic").start() }
        own = null
        releaseHeadset()
        if (busy.value == "Connecting the headset's microphone…") busy.value = null
        recording.value = false
        if (!ConversationSession.running) VoiceHub.conversationWake(false)
        return synchronized(recLock) {
            val all = FloatArray(recSamples)
            var k = 0
            for (x in recorded) { val n = minOf(x.size, all.size - k); System.arraycopy(x, 0, all, k, n); k += n }
            recorded = ArrayList()
            recSamples = 0
            all
        }
    }

    /** Decodes [audio] as a conversation would, off the main thread; [done] on the main thread with (heard, ms, language). */
    private fun decode(audio: FloatArray, done: (String, Long, String?) -> Unit) {
        val decoder = VoiceEngines.segmentDecoder()
        if (decoder == null || !decoder.available) {
            problem.value = "The check needs the speech recognition model: download it in the Robot app (Model › Voice models)."
            return
        }
        busy.value = "Decoding…"
        work.execute {
            val r = try { ConversationEngine.transcribe(this, decoder, audio, LangLabel.yours()) } catch (t: Throwable) { RLog.w("check_decode", t); null }
            runOnUiThread {
                busy.value = null
                if (r == null) problem.value = "Robot couldn't decode that. Try again." else done(r.first, r.second, r.third.lang)
            }
        }
    }

    private fun copyReport(sentences: Boolean) {
        logResults()
        val text = CheckReport.text(Results.lines.toList(), sentences, BuildConfig.VERSION_NAME)
        val clip = ClipData.newPlainText("Transcription check", text)
        // Your words and other people's: kept out of the clipboard's preview.
        if (sentences) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        var mode by remember { mutableStateOf(CheckMode.READ) }
        var reader by remember { mutableIntStateOf(1) }
        var include by remember { mutableStateOf(false) }
        Scaffold(topBar = {
            TopAppBar(title = { Text("Transcription check") }, navigationIcon = { IconButton(onClick = { finish() }) { Icon(RIcons.Back, "Back") } })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("How well Robot writes down Québec French and English, decoded the way a conversation is. The recordings stay in " +
                    "memory and are dropped once scored. The gate: at most 15% of words wrong over at least 20 French lines from at least " +
                    "2 readers, and at most 8% in English.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    CheckMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(selected = mode == m, onClick = { if (!recording.value) mode = m }, shape = SegmentedButtonDefaults.itemShape(i, CheckMode.entries.size)) {
                            Text(when (m) { CheckMode.READ -> "Read"; CheckMode.FREE -> "Say anything"; CheckMode.CLIP -> "Clips" })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Reader", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(12.dp))
                    for (r in 1..4) {
                        FilterChip(selected = reader == r, onClick = { reader = r }, label = { Text("$r") }, modifier = Modifier.heightIn(min = 48.dp).padding(end = 6.dp))
                    }
                }
                // 0.8: the headset's (or the car's) microphone, while one is connected; its lines are scored apart.
                if (headset.value != null && mode != CheckMode.CLIP) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Microphone", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(12.dp))
                    for (m in CheckMic.entries) {
                        FilterChip(selected = mic.value == m, onClick = { if (!recording.value) mic.value = m },
                            label = { Text(if (m == CheckMic.PHONE) "Phone" else "Headset") }, modifier = Modifier.heightIn(min = 48.dp).padding(end = 6.dp))
                    }
                }
                problem.value?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                busy.value?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                when (mode) {
                    CheckMode.READ -> ReadMode(reader)
                    CheckMode.FREE -> FreeMode(reader)
                    CheckMode.CLIP -> ClipMode(reader)
                }
                ResultsCard(include) { include = it }
                Spacer(Modifier.heightIn(min = 24.dp))
            }
        }
    }

    @Composable
    private fun RecordButton(onDone: (FloatArray) -> Unit) {
        val rec by recording
        if (!rec) Button(onClick = { startRecording() }, enabled = busy.value == null, modifier = Modifier.heightIn(min = 56.dp)) {
            Icon(RIcons.Mic, null)
            Spacer(Modifier.width(8.dp))
            Text("Record")
        } else Column {
            Button(onClick = { val a = stopRecording(); if (a.size >= Pcm.RATE / 2) onDone(a) else problem.value = "That was too short." },
                modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(RIcons.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text("Stop")
            }
            LinearProgressIndicator(progress = { ((level.value + 60f) / 60f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }

    @Composable
    private fun ReadMode(reader: Int) {
        var k by remember { mutableIntStateOf(0) }
        val (lang, sentence) = CheckSentences.ALL[k % CheckSentences.ALL.size]
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sentence ${k % CheckSentences.ALL.size + 1} of ${CheckSentences.ALL.size} · ${CheckReport.langName(lang)}",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(sentence, style = MaterialTheme.typography.headlineSmall)
                Text("Tap Record, read it as you'd say it, tap Stop.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RecordButton { audio ->
                        val audioMs = Pcm.ms(audio.size)
                        val m = recMic
                        decode(audio) { heard, ms, detected ->
                            Results.lines += CheckLine(CheckMode.READ, lang, sentence, heard, detected, ms, audioMs, reader, m)
                            k++
                        }
                    }
                    TextButton(onClick = { k++ }, enabled = !recording.value, modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip") }
                }
            }
        }
    }

    @Composable
    private fun FreeMode(reader: Int) {
        var heard by remember { mutableStateOf<Triple<String, Long, Long>?>(null) }
        var heardMic by remember { mutableStateOf(CheckMic.PHONE) }
        var fixed by remember { mutableStateOf("") }
        var lang by remember { mutableStateOf("fr") }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Say anything, then correct what Robot wrote to exactly what was said. That is the realistic test.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val h = heard
                if (h == null) RecordButton { audio ->
                    val audioMs = Pcm.ms(audio.size)
                    val m = recMic
                    decode(audio) { text, ms, detected ->
                        heard = Triple(text, ms, audioMs)
                        heardMic = m
                        fixed = text
                        lang = detected ?: LangLabel.yours()
                    }
                } else {
                    Text("Robot heard: ${h.first.ifBlank { "(nothing)" }}", style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(fixed, { fixed = it }, label = { Text("What was really said") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = lang == "fr", onClick = { lang = "fr" }, label = { Text("French") }, modifier = Modifier.heightIn(min = 48.dp))
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = lang == "en", onClick = { lang = "en" }, label = { Text("English") }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            Results.lines += CheckLine(CheckMode.FREE, lang, fixed.trim(), h.first, app.heyrobot.voice.ConversationEngine.langId(this@TranscriptionCheckActivity).detect(h.first).lang,
                                h.second, h.third, reader, heardMic)
                            heard = null
                        }, enabled = fixed.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Score it") }
                        TextButton(onClick = { heard = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Discard") }
                    }
                }
            }
        }
    }

    @Composable
    private fun ClipMode(reader: Int) {
        val clips = remember { mutableStateListOf<Uri>() }
        var refs by remember { mutableStateOf<Uri?>(null) }
        var lang by remember { mutableStateOf("fr") }
        val pickClips = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { list -> clips.clear(); clips.addAll(list) }
        val pickText = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> refs = u }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Pick audio clips and a .txt with one line per clip (in the order of the clips' names), or lines of “name<tab>sentence” " +
                    "as in a Common Voice list. Robot reads only the files you pick; nothing is copied or kept.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { pickClips.launch(arrayOf("audio/*")) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (clips.isEmpty()) "Pick clips" else "${clips.size} clip${if (clips.size == 1) "" else "s"}")
                    }
                    OutlinedButton(onClick = { pickText.launch(arrayOf("text/*")) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (refs == null) "Pick the .txt" else "Lines picked")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("The clips are in", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = lang == "fr", onClick = { lang = "fr" }, label = { Text("French") }, modifier = Modifier.heightIn(min = 48.dp))
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = lang == "en", onClick = { lang = "en" }, label = { Text("English") }, modifier = Modifier.heightIn(min = 48.dp))
                }
                Button(onClick = { runClips(clips.toList(), refs, lang, reader) }, enabled = clips.isNotEmpty() && refs != null && busy.value == null,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Run") }
            }
        }
    }

    private fun runClips(clips: List<Uri>, refs: Uri?, lang: String, reader: Int) {
        val decoder = VoiceEngines.segmentDecoder()
        if (decoder == null || !decoder.available) {
            problem.value = "The check needs the speech recognition model: download it in the Robot app (Model › Voice models)."
            return
        }
        if (ConversationSession.running) {
            problem.value = "A transcription is running. Stop it first."
            return
        }
        busy.value = "Reading the clips…"
        problem.value = null
        work.execute {
            val names = clips.associateBy { ClipReader.name(this, it) }
            val text = refs?.let { ClipReader.text(this, it) }
            val pairing = if (text == null) CheckClips.Pairing.Refused("The text file couldn't be read.") else CheckClips.pair(names.keys.toList(), text)
            if (pairing is CheckClips.Pairing.Refused) {
                runOnUiThread { busy.value = null; problem.value = pairing.why }
                return@execute
            }
            val pairs = (pairing as CheckClips.Pairing.Ok).pairs
            var failed = 0
            for ((k, p) in pairs.withIndex()) {
                runOnUiThread { busy.value = "Clip ${k + 1} of ${pairs.size}…" }
                val audio = ClipReader.read(this, names.getValue(p.first))
                if (audio == null || audio.size < Pcm.RATE / 4) { failed++; continue }
                val r = try { ConversationEngine.transcribe(this, decoder, audio, LangLabel.yours()) } catch (t: Throwable) { RLog.w("check_clip_decode", t); null }
                if (r == null) { failed++; continue }
                val line = CheckLine(CheckMode.CLIP, lang, p.second, r.first, r.third.lang, r.second, Pcm.ms(audio.size), reader)
                runOnUiThread { Results.lines += line }
            }
            runOnUiThread {
                busy.value = null
                if (failed > 0) problem.value = "$failed clip${if (failed == 1) "" else "s"} couldn't be decoded."
            }
        }
    }

    @Composable
    private fun ResultsCard(include: Boolean, setInclude: (Boolean) -> Unit) {
        val lines = Results.lines
        if (lines.isEmpty()) return
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Results", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                for (t in CheckReport.totals(lines.toList())) {
                    val good = t.verdict == CheckVerdict.PASSES || t.verdict == CheckVerdict.SO_FAR_PASSES
                    Text(CheckReport.summary(t), style = MaterialTheme.typography.bodyLarge,
                        color = if (good) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                }
                if (CheckReport.totals(lines.toList()).any { it.lang == "fr" && it.verdict == CheckVerdict.FAILS }) {
                    Text("Québec French didn't pass. The README lists what to try next (Robot's own fallbacks for the gate).",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                lines.forEachIndexed { k, l ->
                    Column {
                        Text(CheckReport.line(k + 1, l), style = MaterialTheme.typography.bodyMedium)
                        Text("Heard: ${l.heard.ifBlank { "(nothing)" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TickRow("Include sentences (what was said and heard)", include, setInclude, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { copyReport(include) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy report") }
                    TextButton(onClick = { logResults(); Results.lines.clear(); Results.logged = 0 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear") }
                }
            }
        }
    }

    companion object {
        /** A recording stops growing at 60 s. */
        private const val MAX_SAMPLES = 60 * Pcm.RATE
    }
}
