package com.gama.assistant

import android.content.Context
import android.content.Intent

/**
 * Small cross-process protocol used between the microphone service and the local LLM process.
 * Only short UTF-8 strings cross Binder. Raw audio and model objects never leave their process.
 */
object BrainProcessProtocol {
    const val ACTION_ASK = "com.gama.assistant.brain.ASK"
    const val ACTION_STARTED = "com.gama.assistant.brain.STARTED"
    const val ACTION_RESULT = "com.gama.assistant.brain.RESULT"

    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_QUESTION = "question"
    const val EXTRA_HISTORY = "history"
    const val EXTRA_ANSWER = "answer"
    const val EXTRA_SUCCESS = "success"
    const val EXTRA_PROCESS_PID = "brain_pid"

    const val MAX_QUESTION_CHARS = 1500
    const val MAX_HISTORY_CHARS = 4200
    const val MAX_ANSWER_CHARS = 900

    fun requestIntent(
        context: Context,
        requestId: Long,
        question: String,
        history: String?
    ): Intent = Intent(context, GamaBrainProcessService::class.java).apply {
        action = ACTION_ASK
        putExtra(EXTRA_REQUEST_ID, requestId)
        putExtra(EXTRA_QUESTION, question.trim().take(MAX_QUESTION_CHARS))
        putExtra(EXTRA_HISTORY, history.orEmpty().takeLast(MAX_HISTORY_CHARS))
    }

    fun startedIntent(context: Context, requestId: Long): Intent =
        Intent(ACTION_STARTED).setPackage(context.packageName).apply {
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_PROCESS_PID, android.os.Process.myPid())
        }

    fun resultIntent(
        context: Context,
        requestId: Long,
        answer: String,
        success: Boolean
    ): Intent = Intent(ACTION_RESULT).setPackage(context.packageName).apply {
        putExtra(EXTRA_REQUEST_ID, requestId)
        putExtra(EXTRA_ANSWER, answer.trim().take(MAX_ANSWER_CHARS))
        putExtra(EXTRA_SUCCESS, success)
        putExtra(EXTRA_PROCESS_PID, android.os.Process.myPid())
    }
}
