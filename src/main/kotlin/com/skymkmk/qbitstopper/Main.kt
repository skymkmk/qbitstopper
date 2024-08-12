package com.skymkmk.qbitstopper

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class TorrentList(
    val hash: String, val ratio: Double, val tags: String, val name: String
)

class Main : CliktCommand() {
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

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
        install(HttpCookies)
    }
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

    private suspend fun getUploadingTorrents(): List<TorrentList> {
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

    override fun run() = runBlocking {
        login()
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Logging out...")
            logout()
        })
        while (true) {
            try {
                val lists = getUploadingTorrents()
                for (i in lists) {
                    if (!i.tags.contains("(?i)TJUPT|M-Team|HDFans|PT".toRegex()) && i.ratio > ratioLimit) {
                        suspendTorrent(i.hash)
                        logger.info("Stopped ${i.name}")
                    }
                }
            } catch (e: NoCredentialException) {
                login()
            }
            Thread.sleep(pollingTimesPerMinute * 60000L)
        }
    }
}

fun main(args: Array<String>) = Main().main(args)


class CredentialException : Exception()
class NoCredentialException : Exception()