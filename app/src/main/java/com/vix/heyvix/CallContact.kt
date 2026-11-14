package com.vix.heyvix

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/**
 * Finds a contact by name and starts a phone call to them.
 * Needs the READ_CONTACTS and CALL_PHONE permissions.
 */
object CallContact {

    data class Outcome(val ok: Boolean, val message: String)

    private data class Match(val displayName: String, val number: String)

    // Names people usually save their mother under.
    private val motherWords = setOf(
        "mum", "mummy", "mom", "mommy", "mama", "mother", "ma"
    )

    fun hasPermissions(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    /** [spoken] is what the user said after "call", like "my mum" or "John". */
    fun call(context: Context, spoken: String): Outcome {
        val name = cleanName(spoken)
        if (name.isBlank()) return Outcome(false, "Who should I call?")

        if (!hasPermissions(context)) {
            return Outcome(
                false,
                "VIX needs contacts and phone permission to make calls."
            )
        }

        val match = findContact(context, name)
            ?: return Outcome(false, "I couldn't find $name in your contacts.")

        return try {
            val intent = Intent(
                Intent.ACTION_CALL,
                Uri.fromParts("tel", match.number, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Outcome(true, "Calling ${match.displayName}.")
        } catch (_: SecurityException) {
            Outcome(false, "Android denied the phone permission. Check the app permissions.")
        } catch (_: Exception) {
            Outcome(false, "I couldn't start the call.")
        }
    }

    private fun cleanName(spoken: String): String {
        var t = spoken.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        t = t.replace(Regex("^(my|the)\\s+"), "")
        return t.trim()
    }

    private fun findContact(context: Context, wanted: String): Match? {
        val wantedWords = wanted.split(" ").filter { it.isNotBlank() }
        val isMother = wantedWords.size == 1 && wantedWords[0] in motherWords

        var best: Match? = null
        var bestScore = 0

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        ) ?: return null

        cursor.use {
            val nameCol = it.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val numberCol = it.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            while (it.moveToNext()) {
                val display = it.getString(nameCol) ?: continue
                val number = it.getString(numberCol) ?: continue
                val s = score(display, wanted, wantedWords, isMother)
                if (s > bestScore) {
                    best = Match(display, number)
                    bestScore = s
                }
            }
        }
        return best
    }

    private fun score(
        display: String,
        wanted: String,
        wantedWords: List<String>,
        isMother: Boolean
    ): Int {
        val name = display.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (name.isEmpty()) return 0
        val nameWords = name.split(" ")

        if (isMother) {
            return when {
                name in motherWords -> 100
                nameWords.any { it in motherWords } -> 60
                else -> 0
            }
        }

        return when {
            name == wanted -> 100
            wantedWords.all { it in nameWords } -> 80
            name.contains(wanted) -> 50
            else -> 0
        }
    }
}