/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 vk.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
*/
package com.vk.knet.cornet.experiment

import android.os.SystemClock
import com.vk.knet.core.http.HttpRequest
import com.vk.knet.core.http.metric.HttpMetricsListener
import com.vk.knet.core.http.metric.HttpResponseMeta
import com.vk.knet.cornet.ext.addHeaders
import com.vk.knet.cornet.ext.toHttpMetrics
import com.vk.knet.cornet.ext.toHttpProtocol
import com.vk.knet.cornet.pool.thread.CronetExecutor
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.RequestFinishedInfo
import org.chromium.net.UploadDataProvider
import org.chromium.net.UrlRequest
import java.util.concurrent.Executors

class CronetConnectionBuilder(
    private val engine: ExperimentalCronetEngine,
    private val metric: HttpMetricsListener?
) {

    private val executorMetric = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Cronet-Requests-Metrics")
    }

    fun buildRequest(
        request: HttpRequest,
        executor: CronetExecutor,
        callback: UrlRequest.Callback,
        provider: UploadDataProvider?
    ): UrlRequest {
        //тк все метрики внутри cornet в абсолютных значениях времени прописываем свое
        val requestInitTime = SystemClock.elapsedRealtime()

        // Callback, вызываемый когда запрос заканчивает свою работу (неважно по какой причине)
        val requestCompleteHandler = object : RequestFinishedInfo.Listener(executorMetric) {
            override fun onRequestFinished(requestInfo: RequestFinishedInfo) {
                // Раскидываем событие об HttpMetrics
                if (metric != null) {
                    val info = requestInfo.responseInfo
                    if (info == null) {
                        val metrics = requestInfo.toHttpMetrics(requestInitTime, null)
                        metric.onMetricsCollected(metrics, request, null)
                        return
                    }

                    val headers = info.allHeaders
                    val contentType = headers.getHeader("Content-Type")
                    val contentLength = headers.getHeader("Content-Length")?.toLongOrNull()

                    val data = HttpResponseMeta(
                        info.httpStatusCode,
                        info.httpStatusText,
                        contentType,
                        contentLength,
                        requestInfo.responseInfo?.negotiatedProtocol?.toHttpProtocol(),
                        headers
                    )

                    val metrics = requestInfo.toHttpMetrics(requestInitTime, data)
                    metric.onMetricsCollected(metrics, request, data)
                }
            }
        }


        // Формируем urlRequest, используемый Cronet для запуска запроса
        return engine
            .newUrlRequestBuilder(request.url, callback, executor)
            .disableCache()
            .setHttpMethod(request.method.methodName)
            .setRequestFinishedListener(requestCompleteHandler)
            .addHeaders(request.headers)
            .apply {
                val requestBody = request.body

                if (requestBody != null && provider != null) {
                    if (request.getHeaders("Content-Type") == null) {
                        addHeader("Content-Type", requestBody.getContentType())
                    }

                    if (request.getHeaders("Content-Length") == null) {
                        addHeader("Content-Length", requestBody.getContentLength().toString())
                    }

                    setUploadDataProvider(provider, executor)
                }
            }
            .build()
    }

    private fun Map<String, List<String>>.getHeader(name: String): String? {
        return get(name)?.joinToString()
            ?: get(name.lowercase())?.joinToString()
    }
}
