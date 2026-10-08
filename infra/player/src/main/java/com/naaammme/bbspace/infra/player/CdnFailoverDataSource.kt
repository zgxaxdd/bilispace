package com.naaammme.bbspace.infra.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.naaammme.bbspace.infra.player.cdn.hostFromCdnUrl
import java.io.IOException

/**
 * CDN 失败切换：open() 失败时按候选线路依次重试同一 DataSpec（position 不变，
 * 分段请求按绝对偏移续传）。成功后记住可用线路，后续 chunk 直接复用；
 * 每个加载通道独享一个实例，互不串线。
 */
internal class CdnFailoverDataSourceFactory(
    private val delegateFactory: DataSource.Factory,
    private val candidatesByUrl: Map<String, List<String>>,
    /** 换线真实结果回传（闭环修复）：只有这里知道**到底哪条线通了**。 */
    private val lineOutcomeListener: CdnLineOutcomeListener = CdnLineOutcomeListener.NoOp,
    /** 「实际供数的是哪条 host」回传（会话层记账用）。 */
    private val servingHostListener: CdnServingHostListener = CdnServingHostListener.NoOp
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return CdnFailoverDataSource(
            delegateFactory.createDataSource(),
            candidatesByUrl,
            lineOutcomeListener,
            servingHostListener
        )
    }
}

internal class CdnFailoverDataSource(
    private val delegate: DataSource,
    private val candidatesByUrl: Map<String, List<String>>,
    private val lineOutcomeListener: CdnLineOutcomeListener = CdnLineOutcomeListener.NoOp,
    private val servingHostListener: CdnServingHostListener = CdnServingHostListener.NoOp
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
                // **实际通了的那条**才加分（不是"选中"的那条 —— 这正是闭环断点所在）
                lineOutcomeListener.onOutcome(url, true)
                // 也让会话层知道"现在供数的是哪条"（记账口径修正）。
                hostFromCdnUrl(url).takeIf(String::isNotBlank)?.let(servingHostListener::onServingHost)
                return length
            } catch (error: IOException) {
                lastError = error
                // 失败线路必须被记账，否则下次还会把它排到前面
                lineOutcomeListener.onOutcome(url, false)
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
