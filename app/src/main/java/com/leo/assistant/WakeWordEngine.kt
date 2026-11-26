package com.leo.assistant

import android.content.res.AssetManager

data class FeatureConfig(var sampleRate: Int = 16000, var featureDim: Int = 80, var dither: Float = 0.0f)
data class QnnConfig(var backendLib: String = "", var contextBinary: String = "", var systemLib: String = "", var htpPerfMode: Boolean = false)
data class OnlineTransducerModelConfig(
    var encoder: String = "", var decoder: String = "", var joiner: String = "",
    var qnnConfig: QnnConfig = QnnConfig()
)
data class OnlineModelConfig(
    var transducer: OnlineTransducerModelConfig = OnlineTransducerModelConfig(),
    var tokens: String = "", var numThreads: Int = 2, var debug: Boolean = false,
    var provider: String = "cpu", var modelType: String = "zipformer2"
)
data class KeywordSpotterConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OnlineModelConfig = OnlineModelConfig(),
    var maxActivePaths: Int = 4, var keywordsFile: String = "keywords.txt",
    var keywordsScore: Float = 1.5f, var keywordsThreshold: Float = 0.25f,
    var numTrailingBlanks: Int = 2
)
data class KeywordSpotterResult(val keyword: String, val tokens: Array<String>, val timestamps: FloatArray)

class OnlineStream(var ptr: Long = 0L) {
    init { require(ptr != 0L) { "Failed to create wake-word stream" } }
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) = acceptWaveform(ptr, samples, sampleRate)
    fun release() { if (ptr != 0L) { delete(ptr); ptr = 0L } }
    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun delete(ptr: Long)
    companion object { init { System.loadLibrary("sherpa-onnx-jni") } }
}

class KeywordSpotter(assetManager: AssetManager, val config: KeywordSpotterConfig) {
    private var ptr: Long = newFromAsset(assetManager, config)
    init { require(ptr != 0L) { "Could not load offline wake-word model" } }
    fun createStream(keywords: String): OnlineStream = OnlineStream(createStream(ptr, keywords))
    fun isReady(stream: OnlineStream): Boolean = isReady(ptr, stream.ptr)
    fun decode(stream: OnlineStream) = decode(ptr, stream.ptr)
    fun getResult(stream: OnlineStream): KeywordSpotterResult = getResult(ptr, stream.ptr)
    fun reset(stream: OnlineStream) = reset(ptr, stream.ptr)
    fun release() { if (ptr != 0L) { delete(ptr); ptr = 0L } }
    private external fun newFromAsset(assetManager: AssetManager, config: KeywordSpotterConfig): Long
    private external fun createStream(ptr: Long, keywords: String): Long
    private external fun isReady(ptr: Long, streamPtr: Long): Boolean
    private external fun decode(ptr: Long, streamPtr: Long)
    private external fun getResult(ptr: Long, streamPtr: Long): KeywordSpotterResult
    private external fun reset(ptr: Long, streamPtr: Long)
    private external fun delete(ptr: Long)
    companion object { init { System.loadLibrary("sherpa-onnx-jni") } }
}
