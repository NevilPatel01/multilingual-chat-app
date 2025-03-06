package ca.mohawk.temi8

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var micAnimation: LottieAnimationView
    private lateinit var recordingStatus: TextView
    private lateinit var transcriptionRecyclerView: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    private val messages = mutableListOf<Message>()
    private lateinit var languageSpinner: Spinner

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var audioFile: File? = null
    private lateinit var textToSpeech: TextToSpeech
    private var isTtsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        micAnimation = findViewById(R.id.micAnimation)
        recordingStatus = findViewById(R.id.recordingStatus)
        languageSpinner = findViewById(R.id.languageSpinner)
        transcriptionRecyclerView = findViewById(R.id.transcriptionRecyclerView)

        // Setup RecyclerView
        messageAdapter = MessageAdapter(messages)
        transcriptionRecyclerView.layoutManager = LinearLayoutManager(this)
        transcriptionRecyclerView.adapter = messageAdapter
        val languages = resources.getStringArray(R.array.languages)
        val adapter = ArrayAdapter(this, R.layout.spinner_item, languages)
        adapter.setDropDownViewResource(R.layout.spinner_item)
        languageSpinner.adapter = adapter


        // Initialize TextToSpeech
        textToSpeech = TextToSpeech(this, this)

        // Request permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                updateTtsLanguage()
                // You might want to add a function here to update the OpenAI language if needed
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Mic button click handler
        micAnimation.setOnClickListener {
            if (isRecording) {
                stopRecording()
                micAnimation.pauseAnimation()
                recordingStatus.visibility = View.GONE
                recordingStatus.text = "Start Recording"
                processAudioWithOpenAI()
            } else {
                startRecording()
                micAnimation.playAnimation()
                recordingStatus.visibility = View.VISIBLE
                recordingStatus.text = "Recording is in Progress!!"
            }
            isRecording = !isRecording
        }
    }

    private fun startRecording() {
        try {
            audioFile = File.createTempFile("audio", ".mp3", cacheDir)
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("AudioRecording", "Failed to start recording: ${e.message}")
        }
    }

    private fun stopRecording() {
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
    }

    private fun processAudioWithOpenAI() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val requestFile = audioFile?.asRequestBody("audio/mpeg".toMediaType())
                val audioPart = MultipartBody.Part.createFormData("file", "audio.mp3", requestFile!!)
                val modelPart = MultipartBody.Part.createFormData("model", "whisper-1")

                val languageCodes = resources.getStringArray(R.array.language_codes)
                val selectedLanguage = languageCodes[languageSpinner.selectedItemPosition]
                val languagePart = MultipartBody.Part.createFormData("language", selectedLanguage)

                val transcriptionResponse = RetrofitClient.openAIApiService.transcribeAudio(audioPart, modelPart)
                val userMessage = transcriptionResponse.text


                runOnUiThread {
                    messages.add(Message(userMessage, true))
                    messageAdapter.notifyItemInserted(messages.size - 1)
                    transcriptionRecyclerView.smoothScrollToPosition(messages.size - 1)
                }

                val chatRequest = ChatRequest(
                    model = "gpt-3.5-turbo",
                    messages = listOf(ChatMessage(role = "user", content = userMessage))
                )

                val chatResponse = RetrofitClient.openAIApiService.getChatResponse(chatRequest)
                val responseText = chatResponse.choices.first().message.content

                runOnUiThread {
                    messages.add(Message(responseText, false))
                    messageAdapter.notifyItemInserted(messages.size - 1)
                    transcriptionRecyclerView.smoothScrollToPosition(messages.size - 1)
                    speak(responseText)
                }
            } catch (e: Exception) {
                Log.e("OpenAI", "API Error: ${e.message}")
                if (e is retrofit2.HttpException) {
                    val errorBody = e.response()?.errorBody()?.string()
                    Log.e("OpenAI", "Error body: $errorBody")
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun speak(text: String) {
        if (!isTtsReady) {
            Log.e("TTS", "Engine not initialized")
            return
        }
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            updateTtsLanguage()
        } else {
            Log.e("TTS", "Initialization failed")
        }
    }

    private fun updateTtsLanguage() {
        val languageCodes = resources.getStringArray(R.array.language_codes)
        val locale = Locale.forLanguageTag(languageCodes[languageSpinner.selectedItemPosition])

        if (textToSpeech.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
            textToSpeech.language = locale
        } else {
            Log.w("TTS", "Language $locale not available")
        }
    }


    override fun onDestroy() {
        textToSpeech.shutdown()
        super.onDestroy()
    }
}