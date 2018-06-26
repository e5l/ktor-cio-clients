package io.ktor.experimental.client.redis

// @TODO: Missing commands & generate

suspend fun Redis.ping() = commandString("ping")

suspend fun Redis.append(key: String, value: String) = commandString("append", key, value)
suspend fun Redis.auth(password: String) = commandString("auth", password)
suspend fun Redis.bgrewriteaof() = commandString("bgrewriteaof")
suspend fun Redis.bgsave() = commandString("bgsave")
suspend fun Redis.bitcount(key: String) = commandString("bitcount", key)
suspend fun Redis.bitcount(key: String, start: Int, end: Int) = commandString("bitcount", key, "$start", "$end")

suspend fun Redis.set(key: String, value: String) = commandString("set", key, value)
suspend fun Redis.get(key: String) = commandString("get", key)
suspend fun Redis.del(vararg keys: String) = commandString("del", *keys)
suspend fun Redis.echo(msg: String) = commandString("echo", msg)
suspend fun Redis.expire(key: String, time: Int) = commandString("expire", key, "$time")

suspend fun Redis.hset(key: String, member: String, value: String): Long = commandLong("hset", key, member, value)

suspend fun Redis.hget(key: String, member: String): String? = commandString("hget", key, member)
suspend fun Redis.hincrby(key: String, member: String, increment: Long): Long =
    commandLong("hincrby", key, member, "$increment")

suspend fun Redis.zadd(key: String, vararg scores: Pair<String, Double>): Long {
    val args = kotlin.collections.arrayListOf<Any?>()
    for (score in scores) {
        args += score.second
        args += score.first
    }
    return commandLong("zadd", key, *args.toTypedArray())
}

suspend fun Redis.zadd(key: String, member: String, score: Double): Long = commandLong("zadd", key, score, member)
suspend fun Redis.sadd(key: String, member: String): Long = commandLong("sadd", key, member)
suspend fun Redis.smembers(key: String): List<String> = commandArrayString("smembers", key)

suspend fun Redis.zincrby(key: String, member: String, score: Double) = commandString("zincrby", key, score, member)!!
suspend fun Redis.zcard(key: String): Long = commandLong("zcard", key)
suspend fun Redis.zrevrank(key: String, member: String): Long = commandLong("zrevrank", key, member)
suspend fun Redis.zscore(key: String, member: String): Long = commandLong("zscore", key, member)

suspend fun Redis.hgetall(key: String): Map<String, String> {
    return commandArrayString("hgetall", key).listOfPairsToMap()
}

suspend fun Redis.zrevrange(key: String, start: Long, stop: Long): Map<String, Double> {
    return commandArrayString("zrevrange", key, start, stop, "WITHSCORES").listOfPairsToMap()
        .mapValues { "${it.value}".toDouble() }
}

private fun List<Any?>.listOfPairsToMap(): Map<String, String> {
    val list = this
    return (0 until list.size / 2).map { ("" + list[it * 2 + 0]) to ("" + list[it * 2 + 1]) }.toMap()
}
