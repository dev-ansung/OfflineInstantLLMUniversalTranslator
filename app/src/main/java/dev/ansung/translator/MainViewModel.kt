package dev.ansung.translator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor as AndroidParcelFileDescriptor
import android.annotation.SuppressLint

class MainViewModel : ViewModel() {

    var inputText by mutableStateOf("")
    var translatingText by mutableStateOf("")
    var translatedText by mutableStateOf("")

    var isRecording by mutableStateOf(false)
    var isSpeechProcessing by mutableStateOf(false)
    var isTranslationProcessing by mutableStateOf(false)
    var isInstantTranslationEnabled by mutableStateOf(false)
    var speechStatus by mutableStateOf("Ready")
    var llmStatus by mutableStateOf("Ready")
    var isLlmDownloaded by mutableStateOf(false)

    var inputLanguage by mutableStateOf(Language("Chinese (Traditional)", "繁體中文", "zh-TW", "Taiwan"))
        private set

    fun updateInputLanguage(language: Language) {
        inputLanguage = language
        checkSpeechStatus()
    }
    var outputLanguage by mutableStateOf(Language("Burmese", "မြန်မာစာ", "my-MM", "Myanmar"))
    var customOutputLanguage by mutableStateOf("")

    private var speechRecognizer: SpeechRecognizer? = null
    private val generativeModel = Generation.getClient(
        generationConfig {
            modelConfig = modelConfig {
                releaseStage = ModelReleaseStage.PREVIEW
                preference = ModelPreference.FAST
            }
        }
    )

    private var translationJob: Job? = null
    private var recognitionJob: Job? = null
    private var committedText = ""
    private var lastTranslatedInput = ""

    private var audioFile: File? = null
    private var isRecordingAudio = AtomicBoolean(false)
    private var recordingJob: Job? = null

    private val promptTemplate = (
            "You are a universal translator that can translate text from one language to another. " +
            "Your role is to translate the user input from original language to target language. " +
            "Please only output the translated text without any additional explanation or commentary. " +
            "Input language: ```%s```" +
            "Target language: ```%s```")

    fun setAudioFileDir(dir: File) {
        val file = File(dir, "recorded_audio.wav")
        if (file.exists()) {
            file.delete()
        }
        audioFile = file
    }

    init {
        checkSpeechStatus()
    }

    fun checkSpeechStatus() {
        val recognizer = SpeechRecognition.getClient(
            speechRecognizerOptions {
                locale = inputLanguage.toLocale()
                preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
            }
        )
        viewModelScope.launch {
            try {
                speechStatus = when (val status = recognizer.checkStatus()) {
                    FeatureStatus.AVAILABLE -> "Ready"
                    FeatureStatus.DOWNLOADABLE -> "Model downloadable"
                    FeatureStatus.DOWNLOADING -> "Downloading..."
                    FeatureStatus.UNAVAILABLE -> "Unsupported locale: ${inputLanguage.languageCode}"
                    else -> "Status: $status"
                }
            } catch (e: Exception) {
                speechStatus = "Error checking status: ${e.message}"
            } finally {
                recognizer.close()
            }
        }
    }

    fun checkLlmStatus() {
        viewModelScope.launch {
            val status = generativeModel.checkStatus()
            isLlmDownloaded = status == FeatureStatus.AVAILABLE
            llmStatus = if (isLlmDownloaded) "Model ready" else "Model not downloaded"
        }
    }

    fun downloadLlm() {
        viewModelScope.launch {
            llmStatus = "Downloading LLM..."
            generativeModel.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadCompleted -> {
                        isLlmDownloaded = true
                        llmStatus = "Model ready"
                    }
                    is DownloadStatus.DownloadFailed -> {
                        llmStatus = "Download failed: ${status.e.message}"
                    }
                    is DownloadStatus.DownloadProgress -> {
                        llmStatus = "Downloading..."
                    }
                    else -> {
                        llmStatus = "Downloading..."
                    }
                }
            }
        }
    }

    fun startRecording() {
        if (isRecording) return

        val recognizer = SpeechRecognition.getClient(
            speechRecognizerOptions {
                locale = inputLanguage.toLocale()
                preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
            }
        )
        speechRecognizer = recognizer

        viewModelScope.launch {
            try {
                val status = recognizer.checkStatus()
                when (status) {
                    FeatureStatus.AVAILABLE -> runRecognition(recognizer)
                    FeatureStatus.DOWNLOADABLE -> {
                        speechStatus = "Downloading speech model..."
                        recognizer.download().collect { downloadStatus ->
                            if (downloadStatus is DownloadStatus.DownloadCompleted) {
                                runRecognition(recognizer)
                            }
                        }
                    }
                    else -> {
                        speechStatus = "Speech recognition unavailable: $status"
                    }
                }
            } catch (e: Exception) {
                speechStatus = "Error: ${e.message}"
            }
        }
    }

    private fun runRecognition(recognizer: SpeechRecognizer) {
        isRecording = true
        speechStatus = "Listening..."
        inputText = ""
        committedText = ""
        translatingText = ""
        translatedText = ""
        lastTranslatedInput = ""

        if (isInstantTranslationEnabled) {
            recognitionJob = viewModelScope.launch {
                processRecognitionStream(recognizer.startRecognition(speechRecognizerRequest {
                    audioSource = AudioSource.fromMic()
                }))
            }
        } else {
            startAudioRecording()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecording() {
        val file = audioFile ?: return
        isRecordingAudio.set(true)
        recordingJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    viewModelScope.launch { speechStatus = "AudioRecord init failed" }
                    return@launch
                }

                FileOutputStream(file).use { fos ->
                    recorder.startRecording()
                    val data = ByteArray(bufferSize)
                    while (isRecordingAudio.get()) {
                        val read = recorder.read(data, 0, bufferSize)
                        if (read > 0) {
                            fos.write(data, 0, read)
                        }
                    }
                    recorder.stop()
                    recorder.release()
                }
            } catch (e: Exception) {
                viewModelScope.launch { speechStatus = "Recording error: ${e.message}" }
            }
        }
    }

    private suspend fun processRecognitionStream(stream: Flow<SpeechRecognizerResponse>) {
        isSpeechProcessing = true
        stream.collect { response ->
            when (response) {
                is SpeechRecognizerResponse.PartialTextResponse -> {
                    inputText = committedText + response.text
                }
                is SpeechRecognizerResponse.FinalTextResponse -> {
                    committedText += response.text
                    inputText = committedText
                    maybeTriggerTranslation()
                }
                is SpeechRecognizerResponse.CompletedResponse -> {
                    isSpeechProcessing = false
                    if (isRecording) {
                        stopRecording()
                    } else {
                        speechStatus = "Done"
                        maybeTriggerTranslation(force = true)
                    }
                }
                is SpeechRecognizerResponse.ErrorResponse -> {
                    isSpeechProcessing = false
                    speechStatus = "Error: ${response.e.message}"
                    if (isRecording) {
                        stopRecording()
                    }
                }
            }
        }
        isSpeechProcessing = false
    }

    fun retrySpeechRecognition() {
        processRecordedAudio(isManualRetry = true)
    }

    private fun processRecordedAudio(isManualRetry: Boolean = false) {
        val file = audioFile ?: return
        if (!file.exists()) {
            if (isManualRetry) speechStatus = "No recording found"
            return
        }

        speechRecognizer?.close()
        val recognizer = SpeechRecognition.getClient(
            speechRecognizerOptions {
                locale = inputLanguage.toLocale()
                preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
            }
        )
        speechRecognizer = recognizer

        inputText = ""
        committedText = ""
        translatingText = ""
        translatedText = ""
        lastTranslatedInput = ""

        viewModelScope.launch {
            speechStatus = if (isManualRetry) "Retrying recognition..." else "Processing speech..."
            try {
                val pfd = AndroidParcelFileDescriptor.open(file, AndroidParcelFileDescriptor.MODE_READ_ONLY)
                processRecognitionStream(recognizer.startRecognition(speechRecognizerRequest {
                    audioSource = AudioSource.fromPfd(pfd)
                }))
            } catch (e: Exception) {
                speechStatus = "Recognition error: ${e.message}"
            }
        }
    }

    fun retryTranslation() {
        maybeTriggerTranslation(force = true)
    }

    private fun maybeTriggerTranslation(force: Boolean = false) {
        if (!isInstantTranslationEnabled && !force) return
        if (inputText.isBlank() || (inputText == lastTranslatedInput && !force)) return

        translationJob?.cancel()
        translationJob = viewModelScope.launch {
            if (!force) delay(500) // Debounce for instant translation
            
            isTranslationProcessing = true
            val textToTranslate = inputText
            lastTranslatedInput = textToTranslate

            llmStatus = "Translating..."
            translatingText = ""

            val inputLangDescription = inputLanguage.localName
            val targetLangDescription = customOutputLanguage.ifBlank { outputLanguage.localName }

            val systemPrompt = promptTemplate.format(inputLangDescription, targetLangDescription)

            val request = generateContentRequest(TextPart(textToTranslate)) {
                promptPrefix = PromptPrefix(systemPrompt)
            }

            try {
                generativeModel.generateContentStream(request).collect { chunk ->
                    val newText = chunk.candidates.firstOrNull()?.text ?: ""
                    translatingText += newText
                }
                translatedText = translatingText
                llmStatus = "Done"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    // Job was cancelled, expected
                } else {
                    llmStatus = "Error: ${e.message}"
                }
            } finally {
                isTranslationProcessing = false
            }
        }
    }

    fun stopRecording() {
        isRecording = false
        speechStatus = "Stopped"
        isRecordingAudio.set(false)
        recognitionJob?.cancel()
        
        viewModelScope.launch {
            speechRecognizer?.stopRecognition()
            
            if (!isInstantTranslationEnabled) {
                // Wait for recording to finish writing
                recordingJob?.join()
                processRecordedAudio(isManualRetry = false)
            } else {
                maybeTriggerTranslation(force = true)
            }
        }
    }

    fun hasAudioFile(): Boolean = audioFile?.exists() == true

    fun stopAllTasks() {
        isRecording = false
        speechStatus = "Ready"
        translationJob?.cancel()
        recognitionJob?.cancel()
        viewModelScope.launch {
            speechRecognizer?.stopRecognition()
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.close()
    }
}
