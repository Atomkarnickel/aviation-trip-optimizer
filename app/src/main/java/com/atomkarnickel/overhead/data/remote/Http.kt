package com.atomkarnickel.overhead.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpException(val code: Int, message: String) : IOException(message)

data class HttpResult(val code: Int, val body: String) {
    val ok get() = code in 200..299
}

/** OkHttp-Client mit User-Agent inkl. Kontaktadresse (verlangen planespotters.net u. a.). */
fun buildHttpClient(userAgent: String): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .addInterceptor(Interceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) })
    .build()

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

suspend fun OkHttpClient.getText(url: String): HttpResult =
    newCall(Request.Builder().url(url).build()).await().use { HttpResult(it.code, it.body?.string().orEmpty()) }

suspend fun OkHttpClient.getBytes(url: String): ByteArray? =
    newCall(Request.Builder().url(url).build()).await().use { if (it.isSuccessful) it.body?.bytes() else null }
