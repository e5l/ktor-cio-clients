package com.soywiz.io.ktor.session.redis

import com.soywiz.io.ktor.client.redis.*
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.sessions.*
import kotlinx.coroutines.experimental.io.ByteReadChannel
import kotlinx.coroutines.experimental.io.ByteWriteChannel
import kotlinx.coroutines.experimental.io.reader
import java.io.ByteArrayOutputStream
import java.io.Closeable

// @TODO: TTL + Strategy
class RedisSessionStorage(val redis: Redis, val prefix: String = "session_", val ttlSeconds: Int = 3600) :
    SessionStorage, Closeable {
    private fun buildKey(id: String) = "$prefix$id"

    override suspend fun invalidate(id: String) {
        redis.del(buildKey(id))
    }

    override suspend fun <R> read(id: String, consumer: suspend (ByteReadChannel) -> R): R {
        val key = buildKey(id)
        val result = redis.get(key)
        //println("REDIS READ: '$result'")
        if (result != null) {
            redis.expire(key, ttlSeconds) // Refresh
            return consumer(ByteReadChannel(result.hex))
        } else {
            throw NoSuchElementException("Session $id not found") // @TODO: This seems to be thrown and not catched, so once expired, it fails before reaching routing at all
        }
    }

    override suspend fun write(id: String, provider: suspend (ByteWriteChannel) -> Unit) {
        return provider(reader(getCoroutineContext(), autoFlush = true) {
            val data = ByteArrayOutputStream()
            val temp = ByteArray(1024)
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(temp)
                if (read <= 0) break
                data.write(temp, 0, read)
            }
            val key = buildKey(id)
            redis.set(key, data.toByteArray().hex)
            redis.expire(key, ttlSeconds)
        }.channel)
    }

    override fun close() {
    }
}

internal object RedisSessionStorageSpike {
    data class TestSession(val visits: Int = 0)

    @JvmStatic fun main(args: Array<String>) {
        val redis = Redis()

        embeddedServer(Netty, 8080) {
            install(Sessions) {
                val cookieName = "SESSION4"
                val sessionStorage = RedisSessionStorage(redis, ttlSeconds = 10)
                cookie<TestSession>(cookieName, sessionStorage)
                //header<TestUserSession>(cookieName, sessionStorage) {
                //    transform(SessionTransportTransformerDigest())
                //}
            }
            routing {
                get("/") {
                    val ses = call.sessions.getOrNull<TestSession>() ?: TestSession()
                    call.sessions.set(TestSession(ses.visits + 1))
                    call.respondText("hello: " + ses)
                }
                get("/set") {
                    val ses = call.sessions.getOrNull<TestSession>() ?: TestSession()
                    call.sessions.set(TestSession(ses.visits + 1))
                    call.respondText("ok")
                }
                get("/get") {
                    //call.respondText("ok: " + call.sessions.getOrNull<TestSession>())
                    call.respondText("ok")
                }
            }
        }.apply {
            start(wait = true)
        }
    }
}

inline fun <reified T> CurrentSession.getOrNull(): T? = try { get(findName(T::class)) as T? } catch (e: NoSuchElementException) { null }