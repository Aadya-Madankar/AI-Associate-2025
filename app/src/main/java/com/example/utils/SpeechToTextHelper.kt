package com.example.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

class SpeechToTextHelper(private val context: Context) : RecognitionListener {

    private var speechRecognizer: SpeechRecognizer? = null
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _rmsDb = MutableStateFlow(0f)
    val rmsDb: StateFlow<Float> = _rmsDb

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState

    private val _isRecognizerAvailable = SpeechRecognizer.isRecognitionAvailable(context)

    init {
        initializeRecognizer()
    }

    private fun initializeRecognizer() {
        try {
            if (_isRecognizerAvailable) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(this@SpeechToTextHelper)
                }
            } else {
                _errorState.value = "Speech Recognition not available on this device"
            }
        } catch (e: Exception) {
            Log.e("STT", "Error initializing SpeechRecognizer: ${e.message}", e)
            _errorState.value = "Speech Recognition initialization failure: ${e.message}"
            speechRecognizer = null
        }
    }

    fun startListening() {
        if (!_isRecognizerAvailable || speechRecognizer == null) {
            initializeRecognizer()
        }
        _errorState.value = null
        _recognizedText.value = ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            speechRecognizer?.startListening(intent)
            _isListening.value = true
        } catch (e: Exception) {
            _errorState.value = "Failed to start listening: ${e.message}"
            _isListening.value = false
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        _isListening.value = false
        _rmsDb.value = 0f
    }

    fun cancel() {
        speechRecognizer?.cancel()
        _isListening.value = false
        _rmsDb.value = 0f
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // --- RecognitionListener Callbacks ---

    override fun onReadyForSpeech(params: Bundle?) {
        _isListening.value = true
        _errorState.value = null
    }

    override fun onBeginningOfSpeech() {
        _isListening.value = true
    }

    override fun onRmsChanged(rmsdB: Float) {
        // rmsdB generally ranges from -2 to 10+
        _rmsDb.value = rmsdB
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        _isListening.value = false
        _rmsDb.value = 0f
    }

    override fun onError(error: Int) {
        val message = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Audio permission needed"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Try talking again!"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speak now or wait... Try restarting"
            SpeechRecognizer.ERROR_SERVER -> "Server-side error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech input timeout"
            else -> "Speech recognition error code: $error"
        }
        Log.e("STT", "Error code $error: $message")
        _errorState.value = message
        _isListening.value = false
        _rmsDb.value = 0f
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            _recognizedText.value = matches[0]
        }
        _isListening.value = false
        _rmsDb.value = 0f
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            _recognizedText.value = matches[0]
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
