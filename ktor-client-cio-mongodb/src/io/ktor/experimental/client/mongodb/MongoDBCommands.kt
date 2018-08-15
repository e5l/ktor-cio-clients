package io.ktor.experimental.client.mongodb

import io.ktor.experimental.client.mongodb.bson.*
import io.ktor.experimental.client.mongodb.db.*
import io.ktor.experimental.client.mongodb.util.*
import io.ktor.experimental.client.util.*

class MongoDBDatabase(val mongo: MongoDB, val db: String)

class MongoDBCollection(val db: MongoDBDatabase, val collection: String) {
    val dbName get() = db.db
    val mongo get() = db.mongo
}

operator fun MongoDB.get(db: String): MongoDBDatabase =
    MongoDBDatabase(this, db)

operator fun MongoDBDatabase.get(collection: String): MongoDBCollection =
    MongoDBCollection(this, collection)

@Deprecated("", ReplaceWith("this[collection]"))
fun MongoDBDatabase.collection(collection: String): MongoDBCollection = this[collection]

suspend inline fun MongoDBDatabase.runCommand(
    numberToSkip: Int = 0, numberToReturn: Int = 1,
    mapGen: MutableMap<String, Any?>.() -> Unit
): Reply = mongo.runCommand(
    db,
    mongoMap(mapGen), numberToSkip, numberToReturn
)

/*
"client" to mapOf(
"application" to mapOf("name" to "ktor-client-cio-mongodb"),
//"application" to mapOf("name" to "MongoDB Shell"),
"driver" to mapOf(
    "name" to "ktor-client-cio-mongodb",
    //"version" to "0.0.1"
    //"name" to "MongoDB Internal Client",
    "version" to "3.6.4"
),
"os" to mapOf(
    "type" to "Darwin",
    "name" to "Mac OS X",
    "architecture" to "x86_64",
    "version" to "17.5.0"
)
*/
suspend fun MongoDB.isMaster(): Reply = runCommand("admin") { putNotNull("isMaster", true) }

suspend fun MongoDBCollection.listIndexes(): List<MongoDBIndex> {
    val result = db.runCommand { putNotNull("listIndexes", collection) }
    //println(result.firstDocument)
    val cursor = result.firstDocument["cursor"]
    val firstBatch = Dynamic { cursor["firstBatch"].list as List<BsonDocument> }
    return MongoDBFindResult(
        this,
        Dynamic { cursor["id"].long },
        firstBatch
    ).pagedIterator().toList().map {
        Dynamic {
            MongoDBIndex(
                name = it["name"].str,
                keys = it["key"].map.map { it.key.str to it.value.int },
                ns = it["ns"]?.str,
                unique = it["unique"]?.bool ?: false
            )
        }
    }
}

/**
 * https://docs.mongodb.com/v3.4/reference/command/insert/
 */
suspend fun MongoDBCollection.insert(
    vararg documents: BsonDocument,
    ordered: Boolean? = null,
    writeConcern: BsonDocument? = null,
    bypassDocumentValidation: Boolean? = null
): Reply = db.runCommand {
    putNotNull("insert", collection)
    putNotNull("documents", documents.toList())
    putNotNull("ordered", ordered)
    putNotNull("writeConcern", writeConcern)
    putNotNull("bypassDocumentValidation", bypassDocumentValidation)
}.checkErrors()

/**
 * Example: mongo.eval("admin", "function() { return {a: 10}; }")
 * Returns the result of the function or throws a [MongoDBException] on error.
 */
suspend fun MongoDBDatabase.eval(function: String, vararg args: Any?): Any? {
    return runCommand {
        putNotNull("eval", BsonJavascriptCode(function))
        putNotNull("args", args.toList())
    }.checkErrors().firstDocument["retval"]
}

class MongoDBFindResult(val collection: MongoDBCollection, val cursorId: Long, val batch: List<BsonDocument>) :
    List<BsonDocument> by batch {
    override fun toString(): String = batch.toString()
}

/**
 * https://docs.mongodb.com/v3.4/reference/command/find/
 */
suspend fun MongoDBCollection.findFirstBatch(
    sort: BsonDocument? = null,
    projection: BsonDocument? = null,
    hint: Any? = null,
    skip: Int? = null,
    limit: Int? = null,
    batchSize: Int? = null,
    singleBatch: Boolean? = null,
    comment: String? = null,
    maxScan: Int? = null,
    maxTimeMs: Int? = null,
    readConcern: BsonDocument? = null,
    max: BsonDocument? = null,
    min: BsonDocument? = null,
    returnKey: Boolean? = null,
    showRecordId: Boolean? = null,
    snapshot: Boolean? = null,
    tailable: Boolean? = null,
    oplogReplay: Boolean? = null,
    noCursorTimeout: Boolean? = null,
    awaitData: Boolean? = null,
    allowPartialResults: Boolean? = null,
    collation: BsonDocument? = null,
    filter: (MongoDBQueryBuilder.() -> BsonDocument)? = null
): MongoDBFindResult {
    val result = db.runCommand {
        putNotNull("find", collection)
        if (filter != null) putNotNull("filter", filter(MongoDBQueryBuilder))
        putNotNull("sort", sort)
        putNotNull("projection", projection)
        putNotNull("hint", hint)
        putNotNull("skip", skip)
        putNotNull("limit", limit)
        putNotNull("batchSize", batchSize)
        putNotNull("singleBatch", singleBatch)
        putNotNull("comment", comment)
        putNotNull("maxScan", maxScan)
        putNotNull("maxTimeMs", maxTimeMs)
        putNotNull("readConcern", readConcern)
        putNotNull("max", max)
        putNotNull("min", min)
        putNotNull("returnKey", returnKey)
        putNotNull("showRecordId", showRecordId)
        putNotNull("snapshot", snapshot)
        putNotNull("tailable", tailable)
        putNotNull("oplogReplay", oplogReplay)
        putNotNull("noCursorTimeout", noCursorTimeout)
        putNotNull("awaitData", awaitData)
        putNotNull("allowPartialResults", allowPartialResults)
        putNotNull("collation", collation)
    }.checkErrors()
    val cursor = Dynamic { result.firstDocument["cursor"] }

    val firstBatch = Dynamic { cursor["firstBatch"].list as List<BsonDocument> }
    //println(result)
    return MongoDBFindResult(
        this,
        Dynamic { cursor["id"].long },
        firstBatch
    )
}

/**
 * https://docs.mongodb.com/v3.4/reference/command/aggregate/
 */
suspend fun MongoDBCollection.aggregate(
    vararg pipeline: BsonDocument,
    explain: Boolean? = null,
    allowDiskUse: Boolean? = null
): Reply = db.runCommand {
    putNotNull("aggregate", collection)
    //println(pipeline.toList())
    putNotNull("pipeline", pipeline)
    putNotNull("explain", explain)
    putNotNull("allowDiskUse", allowDiskUse)
}

data class MongoUpdate(
    val u: BsonDocument,
    val upsert: Boolean? = null,
    val multi: Boolean? = null,
    val collation: BsonDocument? = null,
    val q: MongoDBQueryBuilder.() -> BsonDocument
)

/**
 * https://docs.mongodb.com/v3.4/reference/command/update/
 * https://docs.mongodb.com/manual/reference/operator/update-field/
 */
suspend fun MongoDBCollection.update(
    vararg updates: MongoUpdate,
    ordered: Boolean? = null,
    writeConcern: BsonDocument? = null,
    bypassDocumentValidation: Boolean? = null
): BsonDocument {
    //{ok=1, nModified=26, n=26}

    val result = db.runCommand {
        putNotNull("update", collection)
        putNotNull("updates", updates.map { update ->
            mongoMap {
                putNotNull("q", update.q(MongoDBQueryBuilder))
                putNotNull("u", update.u)
                putNotNull("upsert", update.upsert)
                putNotNull("multi", update.multi)
                putNotNull("collation", update.collation)
            }
        })
    }.checkErrors()
    return result.firstDocument
}

/**
 * https://docs.mongodb.com/v3.4/reference/command/delete/
 */
suspend fun MongoDBCollection.delete(
    limit: Boolean,
    collation: BsonDocument? = null,
    ordered: Boolean? = null,
    writeConcern: BsonDocument? = null,
    q: (MongoDBQueryBuilder.() -> BsonDocument)
): BsonDocument {
    val result = db.runCommand {
        putNotNull("delete", collection)
        putNotNull("deletes", listOf(
            mongoMap {
                putNotNull("q", q.invoke(MongoDBQueryBuilder))
                putNotNull("limit", if (limit) 1 else 0)
                putNotNull("collation", collation)
            }
        ))
        putNotNull("ordered", ordered)
        putNotNull("writeConcern", writeConcern)
    }.checkErrors()
    return result.firstDocument
}

/**
 * https://docs.mongodb.com/manual/reference/command/createIndexes/
 */
data class MongoDBIndex(
    val name: String,
    val keys: List<Pair<String, Int>>,
    val ns: String? = null,
    val unique: Boolean? = null,
    val background: Boolean? = null,
    val partialFilterExpression: BsonDocument? = null,
    val sparse: Boolean? = null,
    val expireAfterSeconds: Int? = null,
    val storageEngine: BsonDocument? = null,
    val weights: BsonDocument? = null,
    val default_language: String? = null,
    val language_override: String? = null,
    val textIndexVersion: Int? = null,
    val _2dsphereIndexVersion: Int? = null,
    val bits: Int? = null,
    val min: Number? = null,
    val max: Number? = null,
    val bucketSize: Number? = null,
    val collation: BsonDocument? = null
)

/**
 * https://docs.mongodb.com/manual/reference/command/createIndexes/
 */
suspend fun MongoDBCollection.createIndexes(
    vararg indexes: MongoDBIndex,
    writeConcern: BsonDocument? = null
): BsonDocument {
    val result = db.runCommand {
        putNotNull("createIndexes", collection)
        putNotNull("indexes", indexes.map { index ->
            mongoMap {
                putNotNull("key", mongoMap {
                    for (key in index.keys) {
                        putNotNull(key.first, key.second)
                    }
                })
                putNotNull("name", index.name)
                putNotNull("unique", index.unique)
                putNotNull("background", index.background)
                putNotNull("partialFilterExpression", index.partialFilterExpression)
                putNotNull("sparse", index.sparse)
                putNotNull("expireAfterSeconds", index.expireAfterSeconds)
                putNotNull("storageEngine", index.storageEngine)
                putNotNull("weights", index.weights)
                putNotNull("default_language", index.default_language)
                putNotNull("language_override", index.language_override)
                putNotNull("textIndexVersion", index.textIndexVersion)
                putNotNull("2dsphereIndexVersion", index._2dsphereIndexVersion)
                putNotNull("bits", index.bits)
                putNotNull("min", index.min)
                putNotNull("max", index.max)
                putNotNull("bucketSize", index.bucketSize)
                putNotNull("collation", index.collation)
            }
        })
        putNotNull("writeConcern", writeConcern)
    }.checkErrors()
    return result.firstDocument
}

suspend fun MongoDBCollection.createIndex(
    name: String,
    vararg keys: Pair<String, Int>,
    unique: Boolean? = null,
    background: Boolean? = null,
    partialFilterExpression: BsonDocument? = null,
    sparse: Boolean? = null,
    expireAfterSeconds: Int? = null,
    storageEngine: BsonDocument? = null,
    weights: BsonDocument? = null,
    default_language: String? = null,
    language_override: String? = null,
    textIndexVersion: Int? = null,
    _2dsphereIndexVersion: Int? = null,
    bits: Int? = null,
    min: Number? = null,
    max: Number? = null,
    bucketSize: Number? = null,
    collation: BsonDocument? = null,
    writeConcern: BsonDocument? = null
): BsonDocument {
    return createIndexes(
        MongoDBIndex(
            name, keys.toList(),
            unique = unique, background = background, partialFilterExpression = partialFilterExpression,
            sparse = sparse, expireAfterSeconds = expireAfterSeconds, storageEngine = storageEngine,
            weights = weights, default_language = default_language, language_override = language_override,
            textIndexVersion = textIndexVersion, _2dsphereIndexVersion = _2dsphereIndexVersion, bits = bits,
            min = min, max = max, bucketSize = bucketSize, collation = collation
        ),
        writeConcern = writeConcern
    )
}

suspend fun MongoDBFindResult.pagedIterator(): SuspendingIterator<BsonDocument> {
    var current = this
    return object : SuspendingIterator<BsonDocument> {
        private val hasMoreInBatch get() = pos < current.batch.size
        private val hasMoreBatches get() = current.batch.isNotEmpty()
        var pos = 0

        private suspend fun getMore() {
            if (hasMoreBatches) {
                current = current.getMore()
            }
            pos = 0
        }

        private suspend fun getMoreIfRequired() {
            if (!hasMoreInBatch) getMore()
        }

        override suspend fun hasNext(): Boolean {
            getMoreIfRequired()
            return hasMoreInBatch
        }

        override suspend fun next(): BsonDocument {
            getMoreIfRequired()
            if (!hasMoreInBatch) throw NoSuchElementException()
            return current.batch[pos++]
        }
    }
}

suspend fun MongoDBFindResult.getMore() = this.collection.getMore(cursorId)

suspend fun MongoDBCollection.getMore(
    cursorId: Long,
    batchSize: Int? = null,
    maxTimeMS: Int? = null
): MongoDBFindResult {
    val result = db.runCommand {
        putNotNull("getMore", cursorId)
        putNotNull("collection", collection)
        putNotNull("batchSize", batchSize)
        putNotNull("batchSize", maxTimeMS)
    }
    return MongoDBFindResult(
        this,
        cursorId,
        Dynamic { result.firstDocument["cursor"]["nextBatch"].list as List<BsonDocument> })
}