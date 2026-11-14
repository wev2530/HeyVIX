package com.vix.heyvix

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/**
 * Finds contacts by name and starts a phone call.
 * One match: calls it. Several matches: shows a list to choose from.
 * Needs the READ_CONTACTS and CALL_PHONE permissions.
 */
object CallContact {

    data class Outcome(val ok: Boolean, val message: String)

    data class Candidate(val name: String, val number: String)

    // Names people usually save their parents under. Add more here if needed.
    private val motherWords = setOf(
        "mum", "mummy", "mom", "mommy", "mama", "mother", "ma"
    )
    private val fatherWords = setOf(
        "dad", "daddy", "papa", "father", "pa", "pop", "pops", "baba"
    )

    private const val MAX_CHOICES = 20

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

        val matches = findContacts(context, name)
        return when {
            matches.isEmpty() ->
                Outcome(false, "I couldn't find $name in your contacts.")

            matches.size == 1 -> dial(context, matches[0])

            else -> showChoices(context, name, matches)
        }
    }

    /** Starts the call to one chosen contact. */
    fun dial(context: Context, who: Candidate): Outcome {
        return try {
            val intent = Intent(
                Intent.ACTION_CALL,
                Uri.fromParts("tel", who.number, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Outcome(true, "Calling ${who.name}.")
        } catch (_: SecurityException) {
            Outcome(false, "Android denied the phone permission. Check the app permissions.")
        } catch (_: Exception) {
            Outcome(false, "I couldn't start the call.")
        }
    }

    private fun showChoices(
        context: Context,
        name: String,
        matches: List<Candidate>
    ): Outcome {
        return try {
            val names = Array(matches.size) { matches[it].name }
            val numbers = Array(matches.size) { matches[it].number }
            val intent = Intent(context, PermissionActivity::class.java)
                .putExtra(PermissionActivity.EXTRA_CHOICE_NAMES, names)
                .putExtra(PermissionActivity.EXTRA_CHOICE_NUMBERS, numbers)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Outcome(true, "I found ${matches.size} contacts for $name. Choose who to call.")
        } catch (_: Exception) {
            Outcome(false, "I found several contacts for $name but couldn't show the list.")
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

    private fun findContacts(context: Context, wanted: String): List<Candidate> {
        val wantedWords = wanted.split(" ").filter { it.isNotBlank() }

        // "mum" or "dad" also finds Mummy, Mama, Daddy, Papa and so on.
        val group: Set<String>? = if (wantedWords.size == 1) {
            when (wantedWords[0]) {
                in motherWords -> motherWords
                in fatherWords -> fatherWords
                else -> null
            }
        } else null

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        ) ?: return emptyList()

        // Best score per phone number, so the same number is listed once.
        val bestByNumber = LinkedHashMap<String, Pair<Int, Candidate>>()

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
                val s = score(display, wanted, wantedWords, group)
                if (s <= 0) continue

                val key = number.filter { c -> c.isDigit() }
                    .ifEmpty { number }
                val old = bestByNumber[key]
                if (old == null || s > old.first) {
                    bestByNumber[key] = Pair(s, Candidate(display, number))
                }
            }
        }

        return bestByNumber.values
            .sortedWith(
                compareByDescending<Pair<Int, Candidate>> { p -> p.first }
                    .thenBy { p -> p.second.name.lowercase() }
            )
            .map { p -> p.second }
            .take(MAX_CHOICES)
    }

    private fun score(
        display: String,
        wanted: String,
        wantedWords: List<String>,
        group: Set<String>?
    ): Int {
        val name = display.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (name.isEmpty()) return 0
        val nameWords = name.split(" ")

        if (group != null) {
            return when {
                name in group -> 100
                nameWords.any { it in group } -> 60
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