package com.geode.launcher

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * Fallback controls through Android's published media session.
 * The user must explicitly enable this app's notification listener in Android settings.
 */
object SpotifyMediaSessionFallback {
    private const val TAG = "SpotifyMediaFallback"
    private const val SPOTIFY_PACKAGE = "com.spotify.music"

    /**
     * Returns true when this fallback handled the request (including prompting for permission),
     * so the caller does not also attempt the failing App Remote connection.
     */
    @JvmStatic
    fun handleAction(context: GeometryDashActivity, action: Int): Boolean {
        if (!hasNotificationAccess(context)) {
            AlertDialog.Builder(context)
                .setTitle("Permitir control multimedia")
                .setMessage("Para controlar Spotify sin App Remote, activa el acceso a notificaciones de Geode Launcher. Android mostrará su pantalla de ajustes; concede el permiso manualmente y vuelve al juego.")
                .setNegativeButton("Ahora no") { dialog, _ -> dialog.dismiss() }
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure {
                        Log.e(TAG, "Could not open notification-listener settings", it)
                        Toast.makeText(context, "Abre Ajustes > Acceso a notificaciones", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
            return true
        }

        return try {
            val listener = ComponentName(context, SpotifyMediaNotificationListener::class.java)
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            val spotify = manager.getActiveSessions(listener)
                .firstOrNull { it.packageName == SPOTIFY_PACKAGE }
            if (spotify == null) {
                Toast.makeText(context, "Spotify no tiene una sesión multimedia activa. Inicia una canción y vuelve a intentarlo.", Toast.LENGTH_LONG).show()
                Log.w(TAG, "No active Spotify media session found")
                return true
            }
            dispatch(spotify, action)
            Toast.makeText(context, "Orden enviada a Spotify", Toast.LENGTH_SHORT).show()
            true
        } catch (error: SecurityException) {
            Log.e(TAG, "Notification-listener access is not enabled", error)
            Toast.makeText(context, "Activa el acceso a notificaciones de Geode Launcher en Ajustes de Android.", Toast.LENGTH_LONG).show()
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Media-session fallback failed", error)
            Toast.makeText(context, "No se pudo controlar la sesión multimedia de Spotify.", Toast.LENGTH_LONG).show()
            true
        }
    }

    private fun hasNotificationAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val target = ComponentName(context, SpotifyMediaNotificationListener::class.java)
        return enabled.split(":").any { ComponentName.unflattenFromString(it) == target }
    }

    private fun dispatch(controller: MediaController, action: Int) {
        val controls = controller.transportControls
        when (action) {
            0 -> controls.pause()
            1 -> controls.play()
            2 -> controls.skipToNext()
            3 -> controls.skipToPrevious()
            else -> throw IllegalArgumentException("Unsupported media action: $action")
        }
        Log.i(TAG, "Dispatched action=$action to ${controller.packageName}")
    }
}

/** Declared as a notification listener so Android can authorize access to active media sessions. */
class SpotifyMediaNotificationListener : android.service.notification.NotificationListenerService()
