package com.naaammme.bbspace.infra.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * CDN 失败切换：open() 失败时按候选线路依次重试同一 DataSpec（position 不变，
 * 分段请求按绝对偏移续传）。成功后记住可用线路，后续 chunk 直接复用；
 * 每个加载通道独享一个实例，互不串线。
 */
internal class CdnFailoverDataSourceFactory(
    private val delegateFactory: DataSource.Factory,
    private val candidatesByUrl: Map<String, List<String>>
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return CdnFailoverDataSource(delegateFactory.createDataSource(), candidatesByUrl)
    }
}

internal class CdnFailoverDataSource(
    private val delegate: DataSource,
    private val candidatesByUrl: Map<String, List<String>>
) : DataSource {
    private var preferredIndex = 0

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val candidates = candidatesByUrl[dataSpec.uri.toString()] ?: emptyList()
        if (candidates.size <= 1) {
            return delegate.open(dataSpec)
        }
        val startIndex = preferredIndex.coerceIn(0, candidates.size - 1)
        var lastError: IOException? = null
        for (offset in candidates.indices) {
            val index = (startIndex + offset) % candidates.size
            val url = candidates[index]
            // index==0 即主线路，与 dataSpec 原始 URI 一致，直接透传（预取缓存仅对主线路生效）
            val spec = if (index == 0) {
                dataSpec
            } else {
                dataSpec.buildUpon().setUri(url).build()
            }
            try {
                val length = delegate.open(spec)
                preferredIndex = index
                return length
            } catch (error: IOException) {
                lastError = error
                runCatching { delegate.close() }
            }
        }
        // 全部候选失败：起点轮转一位，避免 loader 重试时反复从同一失效线路开始
        preferredIndex = (startIndex + 1) % candidates.size
        throw lastError ?: IOException("所有播放线路均打开失败")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return delegate.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() {
        delegate.close()
    }
}
