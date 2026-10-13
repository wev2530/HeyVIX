package com.vix.heyvix

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.view.View
import android.view.WindowManager

/**
 * Lets you pick VIX as the phone's "Digital assistant app".
 * When the assistant trigger is used (for example holding the power
 * button), VIX now shows a small overlay on top of whatever app is open.
 */

/** Android needs this service to list VIX as an assistant. */
class VixVoiceInteractionService : VoiceInteractionService()

/** Creates a session each time the assistant trigger is used. */
class VixVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        VixVoiceInteractionSession(this)
}

/** Shows the VIX overlay on top of the current app. */
class VixVoiceInteractionSession(context: Context) :
    VoiceInteractionSession(context) {

    private val panel: VixOverlayPanel by lazy {
        VixOverlayPanel(
            context,
            { command -> openVix(command) },
            { hide() }
        )
    }

    override fun onCreateContentView(): View = panel.root

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        try {
            window?.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        } catch (_: Exception) {
        }

        panel.onShown()
    }

    override fun onHide() {
        panel.onHidden()
        super.onHide()
    }

    override fun onDestroy() {
        panel.onHidden()
        super.onDestroy()
    }

    /** Opens the full VIX app, optionally with a command to run. */
    private fun openVix(command: String?) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )

        if (!command.isNullOrBlank()) {
            intent.putExtra(WakeWordService.EXTRA_WAKE, true)
            intent.putExtra(WakeWordService.EXTRA_WAKE_COMMAND, command)
        }

        try {
            startAssistantActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }

        hide()
    }
}

/**
 * Android's assistant setup asks for a recognition service.
 * VIX does its own listening, so this one does nothing.
 */
class VixRecognitionService : RecognitionService() {
    override fun onStartListening(
        recognizerIntent: Intent?,
        listener: Callback?
    ) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
