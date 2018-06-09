package io.ktor.experimental.client.amazon.auth

import io.ktor.http.*
import io.ktor.util.*
import java.io.*
import java.net.*
import java.security.*
import java.text.*
import java.util.*
import javax.crypto.*
import javax.crypto.spec.*
import kotlin.math.*

/**
 * NOTE: To make this compatible with WebCrypto, functions might require suspend since that API is asynchronous.
 */
object AmazonAuth {
    class Credentials(val accessKey: String, val secretKey: String)

    fun getCredentials(accessKey: String? = null, secretKey: String? = null): Credentials {
        var finalAccessKey = accessKey
        var finalSecretKey = secretKey

        if (finalAccessKey.isNullOrEmpty()) {
            finalAccessKey = System.getenv("AWS_ACCESS_KEY_ID")?.trim()
            finalSecretKey = System.getenv("AWS_SECRET_KEY")?.trim()
        }

        if (finalAccessKey.isNullOrEmpty()) {
            val userHome = System.getProperty("user.home")
            val credentialsFile = File(userHome, ".aws/credentials")
            if (credentialsFile.exists() && credentialsFile.canRead()) {
                val credentials = credentialsFile.readText()
                finalAccessKey = (Regex("aws_access_key_id\\s+=\\s+(.*)")
                    .find(credentials)?.groupValues?.getOrElse(1) { "" } ?: "").trim()
                finalSecretKey = (Regex("aws_secret_access_key\\s+=\\s+(.*)")
                    .find(credentials)?.groupValues?.getOrElse(1) { "" } ?: "").trim()
            }
        }
        return if (finalAccessKey != null && finalSecretKey != null) {
            Credentials(finalAccessKey, finalSecretKey)
        } else {
            Credentials("", "")
        }
    }

    object V1 {
        val DATE_FORMAT = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

        private fun macProcess(key: ByteArray, algo: String, data: ByteArray): ByteArray =
            Mac.getInstance(algo).apply { init(SecretKeySpec(key, algo)) }.doFinal(data)

        fun macProcessStringsB64(key: String, algo: String, data: String): String =
            Base64.getEncoder().encodeToString(
                macProcess(
                    key.toByteArray(),
                    algo,
                    data.toByteArray()
                )
            )

        fun getAuthorization(
            accessKey: String,
            secretKey: String,
            method: HttpMethod,
            cannonicalPath: String,
            headers: Headers
        ): String {
            val contentType = headers["content-type"] ?: ""
            val contentMd5 = headers["content-md5"] ?: ""
            val date = headers["date"] ?: ""

            val amzHeaders = LinkedHashMap<String, String>()

            for ((key, value) in headers.flattenEntries()) {
                val k = key.toLowerCase()
                val v = value.trim()
                if (k.startsWith("x-amz")) amzHeaders[k] = v
            }

            val canonicalizedAmzHeaders =
                amzHeaders.entries.sortedBy { it.key }.joinToString { "${it.key}:${it.value}\n" }
            val toSign =
                "${method.value}\n$contentMd5\n$contentType\n$date\n$canonicalizedAmzHeaders$cannonicalPath"
            val signature = macProcessStringsB64(
                secretKey,
                "HmacSHA1",
                toSign
            )
            return "AWS $accessKey:$signature"
        }
    }

    // http://docs.aws.amazon.com/general/latest/gr/signature-v4-examples.html
    object V4 {
        //val DATE_FORMAT = SimplerDateFormat("YYYYMMdd'T'HHmmss'Z'")

        fun getSignedHeaders(headers: Headers): String {
            return headers.entries().map { it.key.toLowerCase() to it.value.map(String::trim).joinToString(",") }
                .sortedBy { it.first }.map { it.first }.joinToString(";")
        }

        fun getCannonicalRequest(method: HttpMethod, url: URL, headers: Headers, payload: ByteArray): String {
            var canonicalRequest = ""
            canonicalRequest += method.value + "\n"
            canonicalRequest += "/" + url.path.trim('/') + "\n"
            canonicalRequest += url.query + "\n"

            for ((k, v) in headers.entries().map { it.key.toLowerCase() to it.value.map(String::trim).joinToString(",") }.sortedBy { it.first }) {
                canonicalRequest += "$k:$v\n"
            }
            canonicalRequest += "\n"
            canonicalRequest += "${getSignedHeaders(headers)}\n"
            canonicalRequest += hex(SHA256(payload))
            return canonicalRequest
        }

        fun getCannonicalRequestHash(
            method: HttpMethod,
            url: URL,
            headers: Headers,
            payload: ByteArray
        ): ByteArray {
            return SHA256(
                getCannonicalRequest(
                    method,
                    url,
                    headers,
                    payload
                ).toByteArray(Charsets.UTF_8)
            )
        }

        fun SHA256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
        fun HMAC(data: ByteArray, key: ByteArray): ByteArray {
            val algo = "HmacSHA256"
            return Mac.getInstance(algo).apply { init(SecretKeySpec(key, algo)) }.doFinal(data)
        }

        fun HmacSHA256(data: String, key: ByteArray): ByteArray =
            HMAC(data.toByteArray(Charsets.UTF_8), key)

        fun getSignatureKey(
            key: String,
            dateStamp: String,
            regionName: String,
            serviceName: String
        ): ByteArray {
            val kSecret = ("AWS4$key").toByteArray(Charsets.UTF_8)
            val kDate = HmacSHA256(dateStamp, kSecret)
            val kRegion = HmacSHA256(regionName, kDate)
            val kService = HmacSHA256(serviceName, kRegion)
            val kSigning = HmacSHA256("aws4_request", kService)
            return kSigning
        }

        fun getStringToSign(
            method: HttpMethod,
            url: URL,
            headers: Headers,
            payload: ByteArray,
            region: String,
            service: String
        ): String {
            val date = headers["X-Amz-Date"]!!
            val ddate = date.substring(0, min(8, date.length))
            var stringToSign = ""
            stringToSign += "AWS4-HMAC-SHA256\n"
            stringToSign += date + "\n"
            stringToSign += "$ddate/$region/$service/aws4_request\n"
            stringToSign += hex(
                getCannonicalRequestHash(
                    method,
                    url,
                    headers,
                    payload
                )
            )
            return stringToSign
        }

        fun getSignature(
            key: String,
            method: HttpMethod,
            url: URL,
            headers: Headers,
            payload: ByteArray,
            region: String,
            service: String
        ): String {
            val date = headers["X-Amz-Date"]!!
            val ddate = date.substring(0, min(8, date.length))
            val derivedSigningKey =
                getSignatureKey(key, ddate, region, service)
            val stringToSign =
                getStringToSign(
                    method,
                    url,
                    headers,
                    payload,
                    region,
                    service
                )
            return hex(
                HMAC(
                    stringToSign.toByteArray(Charsets.UTF_8),
                    derivedSigningKey
                )
            )
        }

        fun getAuthorization(
            accessKey: String,
            secretKey: String,
            method: HttpMethod,
            url: URL,
            headers: Headers,
            payload: ByteArray,
            region: String,
            service: String
        ): String {
            val date = headers["X-Amz-Date"]!!
            val ddate = date.substring(0, min(8, date.length))
            val signedHeaders = getSignedHeaders(headers)

            //AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7
            val signature = getSignature(
                secretKey,
                method,
                url,
                headers,
                payload,
                region,
                service
            )
            return "AWS4-HMAC-SHA256 Credential=$accessKey/$ddate/$region/$service/aws4_request, SignedHeaders=$signedHeaders, Signature=$signature"
        }

        fun signHeaders(
            accessKey: String,
            secretKey: String,
            method: HttpMethod,
            url: URL,
            headers: Headers,
            payload: ByteArray,
            region: String,
            service: String
        ): Headers {
            return Headers.build {
                appendAll(headers)
                set(
                    "Authorization",
                    getAuthorization(
                        accessKey,
                        secretKey,
                        method,
                        url,
                        headers,
                        payload,
                        region,
                        service
                    )
                )
            }
        }
    }
}