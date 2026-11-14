package com.vix.heyvix

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * An invisible screen that asks for the contacts and phone permissions,
 * then makes the call VIX was asked to make.
 */
class PermissionActivity : Activity() {

    companion object {
        const val EXTRA_CALL_NAME = "call_name"
        private const val REQUEST_CALL = 41
    }

    private var callName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}