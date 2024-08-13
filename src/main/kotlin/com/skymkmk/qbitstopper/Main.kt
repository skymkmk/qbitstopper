package com.skymkmk.qbitstopper

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.cooccurring
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class Torrent(
    val hash: String, val ratio: Double, val tags: String, val name: String
)

@Serializable
data class TgResponse(
    val ok: Boolean, val description: String? = null
)

class TelegramOption : OptionGroup() {
    val telegramToken by option("--tg-token", help = "Telegram Bot Token").required()
    val telegramChatId by option("--tg-cid", help = "Telegram Notify Chat ID")
}

val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
        })
    }
    install(HttpCookies)
}

class Main private constructor() : CliktCommand() {
    companion object {
        val instance = Main()
    }

    private val host by option(help = "qBittorrent host, should with protocol but not with port. For example: http://127.0.0.1 or https://example.net").default(
        "127.0.0.1"
    )
    private val port by option(help = "qBittorrent port").int().default(8080)
    private val username by option("-u", "--username", help = "qBittorrent username").required()
    private val password by option("-p", "--password", help = "qBittorrent password").required()
    private val pollingTimesPerMinute by option(
        "-t", "--polling-times-per-minute", help = "Polling times per minute to communicate with qBittorrent"
    ).int().default(1)
    private val ratioLimit by option("-r", "--ratio", help = "The sharing ratio limit to qBittorrent torrent").double()
        .default(2.0)
    private val telegramOption by TelegramOption().cooccurring()

    private val logger = LoggerFactory.getLogger("com.skymkmk.qbitstopper.main")

    private suspend fun login() {
        val url = URLBuilder(host)
        url.port = port
        url.path("/api/v2/auth/login")
        if (!client.submitForm(url = url.buildString(), formParameters = parameters {
                append("username", username)
                append("password", password)
            }).headers.contains("Set-Cookie")) {
            logger.error("Username or password wrong!")
            throw CredentialException()
        }
        logger.info("Logged in!")
    }

    private fun logout() {
        val url = URLBuilder(host)
        url.port = port
        url.path("/api/v2/auth/logout")
        runBlocking {
            client.post(url.build())
        }
    }

    private suspend fun getUploadingTorrents(): List<Torrent> {
        val url = URLBuilder(host)
        url.port = port
        url.path("/api/v2/torrents/info")
        url.parameters.append("filter", "seeding")
        val resp = client.get(url.build())
        if (resp.status == HttpStatusCode.Forbidden) throw NoCredentialException()
        return resp.body()
    }

    private suspend fun suspendTorrent(hash: String) {
        val url = URLBuilder(host)
        url.port = port
        url.path("/api/v2/torrents/pause")
        if (client.submitForm(url.buildString(), parameters {
                append("hashes", hash)
            }).status == HttpStatusCode.Forbidden) throw NoCredentialException()
    }

    private suspend fun tgNotify(torrent: Torrent) {
        if (telegramOption != null) {
            repeat(3) {
                val url = "https://api.telegram.org/bot${telegramOption!!.telegramToken}/sendMessage"
                try {
                    val result = client.submitForm(url, parameters {
                        append("chat_id", telegramOption!!.telegramChatId!!)
                        append("text", "${torrent.name} 达到分享率 ${torrent.ratio}，已经暂停")
                    }).body<TgResponse>()
                    if (!result.ok) logger.warn("Failed to send tg notify for ${torrent.name}. Reason: ${result.description}")
                    else return
                } catch (e: SocketTimeoutException) {
                    logger.warn("Cannot connect to telegram server. $e")
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun run() = runBlocking {
        login()
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Logging out...")
            logout()
        })

        tickerFlow(pollingTimesPerMinute * 60000L).map { getUploadingTorrents() }.catch {
            when (it) {
                is NoCredentialException -> login()
                else -> throw it
            }
        }.flatMapMerge { it.asFlow() }
            .filter { !it.tags.contains("(?i)TJUPT|M-Team|HDFans|PT".toRegex()) && it.ratio > ratioLimit }.collect {
                suspendTorrent(it.hash)
                logger.info("Stopped ${it.name}")
                launch { tgNotify(it) }
            }
    }
}

fun main(args: Array<String>) = Main.instance.main(args)


class CredentialException : Exception()
class NoCredentialException : Exception()