package com.naaammme.bbspace.core.search

import android.content.Context
import androidx.room.Room
import com.bapis.bilibili.pagination.Pagination
import com.bapis.bilibili.polymer.app.search.v1.FilterEntries
import com.bapis.bilibili.polymer.app.search.v1.FeedbackItem
import com.bapis.bilibili.polymer.app.search.v1.FeedbackSection
import com.bapis.bilibili.polymer.app.search.v1.FilterValue
import com.bapis.bilibili.polymer.app.search.v1.Item
import com.bapis.bilibili.polymer.app.search.v1.SearchAllRequest
import com.bapis.bilibili.polymer.app.search.v1.SearchAllResponse
import com.bapis.bilibili.polymer.app.search.v1.Sort
import com.naaammme.bbspace.core.common.media.httpsImageUrl
import com.naaammme.bbspace.core.model.SearchFeedbackItem as SearchFdItem
import com.naaammme.bbspace.core.model.SearchFeedbackSec
import com.naaammme.bbspace.core.model.SearchFilter
import com.naaammme.bbspace.core.model.SearchHistoryOrder
import com.naaammme.bbspace.core.model.SearchOp
import com.naaammme.bbspace.core.model.SearchOrder
import com.naaammme.bbspace.core.model.SearchPage
import com.naaammme.bbspace.core.model.SearchReq
import com.naaammme.bbspace.core.model.SearchAuthor
import com.naaammme.bbspace.core.model.SearchVideo
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.VideoTargetTool
import com.naaammme.bbspace.infra.grpc.BiliGrpcClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class SearchRepository @Inject constructor(
    private val grpcClient: BiliGrpcClient,
    private val searchHistoryDao: SearchHistoryDao
) {

    // AP-14：解析移出主线程 —— 调用方（SearchViewModel）在 viewModelScope = Main.immediate
    // 上直调，整页响应的逐项映射（mapVideo 的 URI 解析 / httpsImageUrl 字符串拼接，
    // 约 20 项/页）原先全在 Main 上跑。同仓 HistoryRepository / DynamicRepository /
    // ArticleRecommendRepository 都包了 Dispatchers.Default，唯独搜索漏了。
    suspend fun search(req: SearchReq): SearchPage = withContext(Dispatchers.Default) {
        val reqBody = SearchAllRequest.newBuilder()
            .setKeyword(req.keyword)
            .setOrder(req.order.toProto())
            .setTidList(req.filterMap[CATEGORY_KEY].orEmpty())
            .setDurationList(req.filterMap[DURATION_KEY].orEmpty())
            .setFromSource(FROM_SOURCE)
            .setLocalTime(localTime())
            .setPagination(
                Pagination.newBuilder()
                    .setPageSize(PAGE_SIZE)
                    .setNext(req.next)
                    .build()
            )
            .setUserAct(USER_ACT)
            .setPubTimeBeginS(req.time.beginS)
            .setPubTimeEndS(req.time.endS)
            .build()
            .toBuilder()

        if (req.filterMap.isNotEmpty()) {
            reqBody.putAllFilterMap(req.filterMap)
        }

        val resp = grpcClient.call(
            endpoint = ENDPOINT,
            requestBytes = reqBody.build().toByteArray(),
            parser = SearchAllResponse.parser()
        )

        val authors = mutableListOf<SearchAuthor>()
        val videos = mutableListOf<SearchVideo>()
        for (item in resp.itemList) {
            mapAuthor(item)?.let(authors::add)
            mapVideo(item, resp.trackid)?.let(videos::add)
        }

        SearchPage(
            keyword = resp.keyword.ifBlank { req.keyword },
            authors = authors,
            videos = videos,
            next = resp.pagination.next,
            filters = resp.searchFilter.filterEntriesList.mapNotNull(::mapFilter)
        )
    }

    suspend fun recordHistory(keyword: String) {
        val query = keyword.trim()
        if (query.isBlank()) return
        // AP-23：插入与淘汰同事务 —— 搜索历史很容易累积到几百条，删一条/读一条的
        // 代价随长度线性增长；上限与 playback history 的 trimByUid 同款不对称收口。
        searchHistoryDao.recordAndTrim(
            keyword = query,
            updatedAt = System.currentTimeMillis(),
            limit = SEARCH_HISTORY_MAX_ENTRIES
        )
    }

    suspend fun deleteHistory(keyword: String) {
        val query = keyword.trim()
        if (query.isBlank()) return
        searchHistoryDao.deleteByKeyword(query)
    }

    fun observeHistory(
        order: SearchHistoryOrder
    ): Flow<List<String>> {
        // AP-23：读取补 LIMIT —— 不再全表读
        val source = when (order) {
            SearchHistoryOrder.TIME -> searchHistoryDao.observeTopKeywordsByTime(SEARCH_HISTORY_MAX_ENTRIES)
            SearchHistoryOrder.HOT -> searchHistoryDao.observeTopKeywordsByHot(SEARCH_HISTORY_MAX_ENTRIES)
        }
        // AP-24：同值重复发射驱动下游重组 —— 结构相等去重
        return source.map { list -> list.filter { it.isNotBlank() } }.distinctUntilChanged()
    }

    private fun mapVideo(
        item: Item,
        pageTrackId: String
    ): SearchVideo? {
        if (item.cardItemCase != Item.CardItemCase.AV) return null
        val av = item.av
        val aid = item.param.toLongOrNull() ?: VideoTargetTool.aid(item.uri) ?: return null
        val cid = VideoTargetTool.cid(item.uri)
            ?: av.share.video.cid.takeIf { it > 0L }
            ?: return null
        val target = VideoTarget.Ugc(
            aid = aid,
            cid = cid,
            bvid = VideoTargetTool.bvid(item.uri),
            src = VideoTargetTool.search(
                uri = item.uri,
                fallbackTrackId = pageTrackId
            )
        )
        return SearchVideo(
            aid = aid,
            cid = cid,
            target = target,
            title = av.title,
            cover = av.cover.httpsImageUrl(),
            author = av.author,
            duration = av.duration,
            viewText = av.viewContent.ifBlank { av.play.toString() },
            danmakuText = av.danmaku.toString(),
            publishTimeText = av.showCardDesc2.removePrefix("· ").takeIf(String::isNotBlank),
            reason = av.takeIf { it.hasRcmdReason() }?.rcmdReason?.content?.takeIf(String::isNotBlank),
            feedbacks = av.feedback.sectionsList.mapNotNull(::mapFeedbackSec)
        )
    }

    private fun mapAuthor(item: Item): SearchAuthor? {
        if (item.cardItemCase != Item.CardItemCase.AUTHOR_NEW) return null
        val author = item.authorNew
        val mid = author.mid.takeIf { it > 0L }
            ?: item.param.toLongOrNull()
            ?: return null
        val name = author.title.ifBlank { return null }
        return SearchAuthor(
            mid = mid,
            name = name.replace("<em class=\"keyword\">", "").replace("</em>", ""),
            avatar = author.cover.httpsImageUrl().takeIf(String::isNotBlank),
            sign = author.sign.takeIf(String::isNotBlank),
            fansText = author.fans.toString(),
            archivesText = author.archives.toString(),
            level = author.level
        )
    }

    private fun mapFeedbackSec(sec: FeedbackSection): SearchFeedbackSec? {
        val items = sec.itemsList.mapNotNull(::mapFeedbackItem)
        if (items.isEmpty()) return null
        return SearchFeedbackSec(
            title = sec.title,
            type = sec.type,
            items = items
        )
    }

    private fun mapFeedbackItem(item: FeedbackItem): SearchFdItem? {
        val text = item.text.ifBlank { return null }
        return SearchFdItem(
            id = item.id,
            text = text
        )
    }

    private fun mapFilter(entry: FilterEntries): SearchFilter? {
        val key = entry.filterType.ifBlank { return null }
        val ops = entry.valuesList.mapNotNull(::mapOp)
        if (ops.isEmpty()) return null
        return SearchFilter(
            key = key,
            title = entry.title.ifBlank { key },
            ops = ops,
            single = entry.singleSelect
        )
    }

    private fun mapOp(value: FilterValue): SearchOp? {
        val param = when (value.filterParamCase) {
            FilterValue.FilterParamCase.PARAM -> value.param
            FilterValue.FilterParamCase.SORT -> value.sort.toString()
            FilterValue.FilterParamCase.USER_SORT -> value.userSort.toString()
            FilterValue.FilterParamCase.CATEGORY_SORT -> value.categorySort.toString()
            else -> ""
        }
        if (param.isBlank()) return null
        return SearchOp(
            label = value.value.ifBlank { param },
            param = param,
            isDefault = value.subModuleForNeuron.equals(DEFAULT_SUB, ignoreCase = true)
        )
    }

    private fun SearchOrder.toProto(): Sort {
        return when (this) {
            SearchOrder.DEFAULT -> Sort.SORT_DEFAULT
            SearchOrder.VIEW -> Sort.SORT_VIEW_COUNT
            SearchOrder.PUBDATE -> Sort.SORT_PUBLISH_TIME
            SearchOrder.DANMAKU -> Sort.SORT_DANMAKU_COUNT
        }
    }

    private fun localTime(): Int {
        return TimeZone.getDefault().rawOffset / 3_600_000
    }

    private companion object {
        const val ENDPOINT = "bilibili.polymer.app.search.v1.Search/SearchAll"
        const val FROM_SOURCE = "app_search"
        const val PAGE_SIZE = 20
        const val USER_ACT = "{\"act_seq\":[]}"
        const val DEFAULT_SUB = "default"
        const val CATEGORY_KEY = "category"
        const val DURATION_KEY = "duration"

        /**
         * AP-23：搜索历史上限（表内条数与读取条数同值，口径不漂）。
         * 对照：playback history 一直有 trimByUid 淘汰，搜索历史此前没有任何淘汰路径。
         */
        const val SEARCH_HISTORY_MAX_ENTRIES = 50
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SearchModule {
    @Provides
    @Singleton
    fun provideSearchHistoryDb(
        @ApplicationContext context: Context
    ): SearchHistoryDb {
        return Room.databaseBuilder(
            context,
            SearchHistoryDb::class.java,
            "search_history.db"
        ).build()
    }

    @Provides
    fun provideSearchHistoryDao(db: SearchHistoryDb): SearchHistoryDao {
        return db.dao()
    }
}
