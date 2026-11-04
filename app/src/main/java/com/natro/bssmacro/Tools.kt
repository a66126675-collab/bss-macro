package com.natro.bssmacro

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Roblox {
    const val PACKAGE = "com.roblox.client"
    const val BSS_PLACE = "1537690962"

    fun installed(ctx: Context): Boolean =
        ctx.packageManager.getLaunchIntentForPackage(PACKAGE) != null

    fun open(ctx: Context, privateLink: String): Boolean {
        val link = privateLink.trim()
        val target = if (link.startsWith("http")) link else "roblox://placeId=$BSS_PLACE"
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(target))
                    .setPackage(PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            val i = ctx.packageManager.getLaunchIntentForPackage(PACKAGE)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                true
            } else false
        }
    }
}

object Gemini {
    fun ask(apiKey: String, model: String, system: String, question: String): String {
        return try {
            val body = JSONObject()
                .put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
                )
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", question)))
                    )
                )
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", apiKey)
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    text.take(200)
                }
                "Error $code: $msg"
            } else {
                JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getString("text")
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
