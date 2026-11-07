
package eu.kanade.tachiyomi.extension.es.megabanana

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class MegaBanana : KeiSource() {

    private val apiUrl
        get() = "$baseUrl/wp-json/megabanana/v1"

    override fun getFilterList(data: JsonElement?) = FilterList(
        PublisherFilter(),
    )

    override suspend fun getPopularManga(page: Int): MangasPage {
        return fetchCatalog(page, "", "comics")
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return fetchCatalog(page, "", "comics")
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val category = filters
            .filterIsInstance<PublisherFilter>()
            .firstOrNull()
            ?.category ?: "comics"

        return fetchCatalog(page, query, category)
    }

    private suspend fun fetchCatalog(
        page: Int,
        query: String,
        category: String,
    ): MangasPage {
        val url = "$apiUrl/catalog".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "30")
            .addQueryParameter("category", category)
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("search", query)
                }
            }
            .build()

        val response = client.get(url, Fetch.XHR)
            .parseAs<CatalogDto>()

        val mangas = response.items.mapNotNull { item ->
            if (!item.readerReady) return@mapNotNull null

            SManga.create().apply {
                this.url = "$apiUrl/reader/${item.id}"
                title = item.title
                thumbnail_url = item.thumbnail
                description = item.excerpt
                memo = buildJsonObject {
                    put("web_url", item.url)
                }
            }
        }

        return MangasPage(
            mangas,
            response.page < response.pages,
        )
    }

    override fun getMangaUrl(manga: SManga): String {
        return manga.memo["web_url"]?.jsonPrimitive?.contentOrNull
            ?: baseUrl
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaId = manga.url.toHttpUrl().pathSegments.last()

        val reader = client.get(
            manga.url,
            Fetch.XHR,
        ).parseAs<ReaderDto>()

        manga.title = reader.title

        val chapterList = reader.chapters.map { chapter ->
            SChapter.create().apply {
                url = "$mangaId/${chapter.number}"
                name = chapter.title
                memo = buildJsonObject {
                    put("web_url", getMangaUrl(manga))
                }
            }
        }.reversed()

        return SMangaUpdate(manga, chapterList)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.split("/")
        val chapterNumber = parts.last()
        val webUrl = chapter.memo["web_url"]?.jsonPrimitive?.contentOrNull
            ?: baseUrl

        return webUrl.toHttpUrl().newBuilder()
            .addQueryParameter("capitulo", chapterNumber)
            .build()
            .toString()
    }

    override suspend fun getPageList(
        chapter: SChapter,
    ): List<Page> {
        val parts = chapter.url.split("/")
        val mangaId = parts[0]
        val chapterNumber = parts[1]

        val readerUrl = "$apiUrl/reader/$mangaId"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("chapter", chapterNumber)
            .build()

        val reader = client.get(
            readerUrl,
            Fetch.XHR,
        ).parseAs<ReaderDto>()

        return reader.pages.mapIndexed { index, imageUrl ->
            Page(
                index,
                imageUrl = imageUrl,
            )
        }
    }
}

private class PublisherFilter : Filter.Select<String>(
    "Editorial",
    arrayOf(
        "Todas",
        "Marvel",
        "DC",
        "Image",
        "IDW",
        "Dark Horse",
        "Vertigo",
        "Dynamite",
        "Independientes",
    ),
) {
    val category: String
        get() = categories[state]

    companion object {
        private val categories = arrayOf(
            "comics",
            "marvel",
            "dc",
            "image",
            "idw",
            "dark-horse",
            "vertigo",
            "dynamite",
            "independientes",
        )
    }
}

@Serializable
private class CatalogDto(
    val items: List<CatalogItemDto>,
    val page: Int,
    val pages: Int,
)

@Serializable
private class CatalogItemDto(
    val id: Int,
    val title: String,
    val url: String,
    val thumbnail: String,
    val excerpt: String,
    val readerReady: Boolean,
)

@Serializable
private class ReaderDto(
    val postId: Int,
    val title: String,
    val currentChapter: Int,
    val chapters: List<ReaderChapterDto>,
    val pages: List<String>,
)

@Serializable
private class ReaderChapterDto(
    val number: String,
    val title: String,
    val pageCount: String,
)
