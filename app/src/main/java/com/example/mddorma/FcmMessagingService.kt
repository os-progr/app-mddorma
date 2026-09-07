package com.example.mddorma

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class FcmMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Nuevo token FCM generado: $token")
        
        // Guardar token en SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()

        // Enviar token al servidor backend mddorma.com
        sendTokenToServer(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Mensaje FCM recibido de: ${remoteMessage.from}")

        // 1. Extraer título y mensaje (preferir data payload, fallback a notification payload)
        val data = remoteMessage.data
        val title = data["title"] ?: remoteMessage.notification?.title ?: "mddorma • Nuevo Estreno"
        val body = data["body"] ?: remoteMessage.notification?.body ?: "¡Hay un nuevo episodio disponible para ver!"
        val url = data["url"] ?: "https://mddorma.com"
        val imageUrl = data["image"] ?: remoteMessage.notification?.imageUrl?.toString()

        // 2. Mostrar la notificación nativa
        showNotification(title, body, url, imageUrl)
    }

    private fun showNotification(title: String, body: String, url: String, imageUrl: String?) {
        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("url", url)
            putExtra("from_notification", true)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), intent, flags)
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(getColor(R.color.primary))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))

        // Si incluye imagen (poster o miniatura de dorama), descargarla y aplicarla
        if (!imageUrl.isNullOrEmpty()) {
            val bitmap = getBitmapFromUrl(imageUrl)
            if (bitmap != null) {
                notificationBuilder.setLargeIcon(bitmap)
                notificationBuilder.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .bigLargeIcon(null as Bitmap?)
                        .setSummaryText(body)
                )
            }
        }

        try {
            val notificationManager = NotificationManagerCompat.from(this)
            val notificationId = System.currentTimeMillis().toInt()
            notificationManager.notify(notificationId, notificationBuilder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "Permiso de notificaciones denegado: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error mostrando notificación: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Estrenos y Episodios"
            val descriptionText = "Avisos de nuevos doramas y episodios estrenados en mddorma"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                enableVibration(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun getBitmapFromUrl(imageUrl: String): Bitmap? {
        return try {
            val url = URL(imageUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.connect()
            val input: InputStream = connection.inputStream
            BitmapFactory.decodeStream(input)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo descargar la imagen de notificación: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "MDDormaFCM"
        const val CHANNEL_ID = "mddorma_estrenos"
        const val PREFS_NAME = "mddorma_prefs"
        const val KEY_FCM_TOKEN = "fcm_token"
        const val REGISTER_URL = "https://mddorma.com/api/fcm_register.php"

        fun sendTokenToServer(token: String) {
            thread {
                try {
                    val url = URL(REGISTER_URL)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")

                    val postData = "token=" + URLEncoder.encode(token, "UTF-8") +
                            "&plataforma=android" +
                            "&dispositivo=" + URLEncoder.encode(Build.MODEL, "UTF-8")

                    val writer = OutputStreamWriter(conn.outputStream)
                    writer.write(postData)
                    writer.flush()
                    writer.close()

                    val responseCode = conn.responseCode
                    Log.d(TAG, "Token FCM sincronizado con backend. Response: $responseCode")
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error enviando token FCM al servidor: ${e.message}")
                }
            }
        }
    }
}
