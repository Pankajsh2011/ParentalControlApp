package com.parental.control.app.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*

/**
 * Feature 1: Live Remote Monitoring & WebRTC Streaming
 * Manages WebRTC PeerConnection, Audio/Video tracks, and STUN/TURN ICE candidates.
 */
class LiveStreamManager(private val context: Context) {

    private val tag = "WebRtcManager"
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null

    init {
        initializePeerFactory()
    }

    private fun initializePeerFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val factoryOptions = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .createPeerConnectionFactory()
        Log.d(tag, "WebRTC PeerConnectionFactory initialized successfully.")
    }

    /**
     * Creates an Audio Track for live audio streaming.
     */
    fun createAudioStream(): AudioTrack? {
        val audioConstraints = MediaConstraints()
        val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("PARENTAL_AUDIO_TRACK", audioSource)
        Log.d(tag, "Local audio track created.")
        return localAudioTrack
    }

    /**
     * Initializes Peer Connection with Google's public STUN servers.
     */
    fun createPeerConnection(observer: PeerConnection.Observer): PeerConnection? {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
        localAudioTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("STREAM_MEDIA"))
        }
        return peerConnection
    }

    fun close() {
        peerConnection?.close()
        peerConnection = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        Log.d(tag, "WebRTC stream closed and resources released.")
    }
}
