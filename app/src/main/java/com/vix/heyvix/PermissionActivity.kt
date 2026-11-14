package com.vix.heyvix

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast

/**
 * An invisible screen with two jobs:
 * 1. Ask for the contacts and phone permissions, then make the call.
 * 2. Show the list of matching contacts so the user can choose who to call.
 */
class PermissionActivity : Activity() {

    companion object {
        const val EXTRA_CALL_NAME = "call_name"
        const val EXTRA_CHOICE_NAMES = "choice_names"
        const val EXTRA_CHOICE_NUMBERS = "choice_numbers"
        private const val REQUEST_CALL = 41
    }

    private var callName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Several contacts matched: show the list.
        val names = intent.getStringArrayExtra(EXTRA_CHOICE_NAMES)
        val numbers = intent.getStringArrayExtra(EXTRA_CHOICE_NUMBERS)
        if (names != null && numbers != null &&
            names.isNotEmpty() && names.size == numbers.size
        ) {
            showChoices(names, numbers)
            return
        }

        callName = intent.getStringExtra(EXTRA_CALL_NAME).orEmpty()
        if (callName.isBlank()) {
            finish()
            return
        }

        if (CallContact.hasPermissions(this)) {
            placeCall()
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.CALL_PHONE
                ),
                REQUEST_CALL
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != REQUEST_CALL) {
            finish()
            return
        }

        if (CallContact.hasPermissions(this)) {
            placeCall()
        } else {
            toast("Without contacts and phone permission I can't make calls.")
            finish()
        }
    }

    private fun placeCall() {
        val outcome = CallContact.call(this, callName)
        toast(outcome.message)
        finish()
    }

    private fun showChoices(names: Array<String>, numbers: Array<String>) {
        val labels: Array<CharSequence> =
            Array(names.size) { "${names[it]}\n${numbers[it]}" }

        AlertDialog.Builder(this)
            .setTitle("Who do you want to call?")
            .setItems(labels) { _, which ->
                val outcome = CallContact.dial(
                    this,
                    CallContact.Candidate(names[which], numbers[which])
                )
                if (!outcome.ok) toast(outcome.message)
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}