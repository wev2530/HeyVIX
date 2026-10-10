package com.vix.heyvix

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.MediaStore

/**
 * Handles supported phone commands locally.
 * This class does not call Firebase or the VIX AI API.
 */
class LocalCommandRouter(
    private val context: Context
) {

    data class Result(
        val handled: Boolean,
        val message: String,
        val successful: Boolean = true,
        val needsCameraPermission: Boolean = false
    )

    /**
     * Returns handled = false when the request should be
     * passed to VIX AI or another handler.
     */
    fun handle(rawText: String): Result {
        val command = normalize(rawText)

        if (command.isBlank()) {
            return Result(true, "Please tell me what you want me to do.", false)
        }

        // Flashlight commands
        if (mentionsFlashlight(command)) {
            return when {
                containsAny(
                    command,
                    "turn on", "switch on", "enable",
                    "activate", "torch on", "flashlight on",
                    "flash light on"
                ) || command.endsWith(" on") -> setFlashlight(true)

                containsAny(
                    command,
                    "turn off", "switch off", "disable",
                    "deactivate", "torch off", "flashlight off",
                    "flash light off"
                ) || command.endsWith(" off") -> setFlashlight(false)

                else -> Result(
                    true,
                    "Do you want me to turn the flashlight on or off?",
                    false
                )
            }
        }

        // Camera commands
        if (containsAny(
                command,
                "open camera",
                "launch camera",
                "take a picture",
                "take picture",
                "take a photo",
                "snap a picture",
                "snap picture",
                "snap a photo"
            )
        ) {
            return openCamera()
        }

        // Screen recording and screenshots will be implemented
        // using Android's appropriate system APIs in a later step.
        if (containsAny(
                command,
                "take a screenshot",
                "take screenshot",
                "screen shot",
                "record my screen",
                "record the screen",
                "start screen recording",
                "stop screen recording"
            )
        ) {
            return Result(
                handled = true,
                message = "I recognized that command, but screenshot and screen recording support has not been implemented yet.",
                successful = false
            )
        }

        // Opening installed apps
        val appName = extractAppName(command)
        if (appName != null) {
            return openApp(appName)
        }

        // Not a local command: let the caller decide whether
        // to send this request to VIX AI.
        return Result(
            handled = false,
            message = ""
        )
    }

    private fun normalize(input: String): String {
        var text = input.lowercase().trim()
        text = text.replace(Regex("[^a-z0-9\\s]"), " ")
        text = text.replace(Regex("\\s+"), " ")
        text = text.replace(Regex("^(hey\\s+)?vix\\s*"), "")
        text = text.replace(Regex("^please\\s+"), "")
        return text.trim()
    }

    private fun mentionsFlashlight(command: String): Boolean =
        containsAny(command, "flashlight", "flash light", "torch")

    private fun setFlashlight(turnOn: Boolean): Result {
        if (context.checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result(
                handled = true,
                message = "VIX needs camera permission to control the flashlight. Please grant permission and try again.",
                successful = false,
                needsCameraPermission = true
            )
        }

        return try {
            val manager = context.getSystemService(
                Context.CAMERA_SERVICE
            ) as CameraManager

            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(
                    CameraCharacteristics.FLASH_INFO_AVAILABLE
                ) == true
            }

            if (cameraId == null) {
                return Result(
                    true,
                    "I couldn't find a camera flashlight on this phone.",
                    false
                )
            }

            manager.setTorchMode(cameraId, turnOn)

            Result(
                handled = true,
                message = if (turnOn) {
                    "Flashlight turned on."
                } else {
                    "Flashlight turned off."
                }
            )
        } catch (_: SecurityException) {
            Result(
                true,
                "Android denied access to the flashlight. Check the app permissions.",
                false,
                needsCameraPermission = true
            )
        } catch (_: Exception) {
            Result(
                true,
                "I couldn't change the flashlight. It may be in use by another app.",
                false
            )
        }
    }

    private fun openCamera(): Result {
        return try {
            val intent = Intent(
                MediaStore.ACTION_IMAGE_CAPTURE
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (intent.resolveActivity(context.packageManager) == null) {
                Result(
                    true,
                    "I couldn't find a camera app on this phone.",
                    false
                )
            } else {
                context.startActivity(intent)
                Result(
                    true,
                    "Opening the camera. Take your picture there."
                )
            }
        } catch (_: Exception) {
            Result(true, "I couldn't open the camera app.", false)
        }
    }

    private fun extractAppName(command: String): String? {
        val prefixes = listOf(
            "open ",
            "launch ",
            "start "
        )

        val prefix = prefixes.firstOrNull {
            command.startsWith(it)
        } ?: return null

        val appName = command.removePrefix(prefix).trim()

        if (appName.isBlank()) return null

        // Camera is handled by the camera command above.
        if (appName in listOf("camera", "my camera")) return null

        return appName.removeSuffix(" app").trim()
    }

    private fun openApp(appName: String): Result {
        val requested = appName.lowercase().trim()

        // Known package names for Free Fire games.
        val packageNames = when {
            requested.contains("free fire max") ->
                listOf("com.dts.freefiremax")

            requested == "free fire" ||
                requested.contains("free fire") ->
                listOf(
                    "com.dts.freefireth",
                    "com.dts.freefiremax"
                )

            else -> emptyList()
        }

        for (packageName in packageNames) {
            val intent = context.packageManager
                .getLaunchIntentForPackage(packageName)

            if (intent != null) {
                return launchApp(intent, appName)
            }
        }

        // Search visible launcher apps by their displayed names.
        // Android package-visibility rules may limit these results.
        return try {
            val launcherIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)

            val activities = context.packageManager
                .queryIntentActivities(launcherIntent, 0)

            val match = activities.firstOrNull { info ->
                val label = info.loadLabel(
                    context.packageManager
                ).toString().lowercase().trim()

                label == requested ||
                    label.contains(requested) ||
                    requested.contains(label)
            }

            if (match == null) {
                Result(
                    true,
                    "I couldn't find an installed app named $appName.",
                    false
                )
            } else {
                val intent = context.packageManager
                    .getLaunchIntentForPackage(
                        match.activityInfo.packageName
                    )

                if (intent == null) {
                    Result(
                        true,
                        "I found $appName, but Android didn't provide a way to launch it.",
                        false
                    )
                } else {
                    launchApp(intent, appName)
                }
            }
        } catch (_: Exception) {
            Result(
                true,
                "I couldn't find or open $appName.",
                false
            )
        }
    }

    private fun launchApp(
        intent: Intent,
        appName: String
    ): Result {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Result(true, "Opening $appName.")
        } catch (_: Exception) {
            Result(true, "I couldn't open $appName.", false)
        }
    }

    private fun containsAny(
        text: String,
        vararg phrases: String
    ): Boolean {
        return phrases.any { phrase ->
            text.contains(phrase)
        }
    }
}
