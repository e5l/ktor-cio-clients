package io.ktor.experimental.client.cassandra

import io.ktor.experimental.client.util.*
import io.ktor.experimental.client.util.sync.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.util.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import org.intellij.lang.annotations.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

// https://raw.githubusercontent.com/apache/cassandra/trunk/doc/native_protocol_v3.spec
class Cassandra private constructor(
    private val reader: ByteReadChannel,
    private val writer: ByteWriteChannel,
    private val close: Closeable,
    private val bufferSize: Int = 0x1000,
    private val debug: Boolean = false
) {
    object Opcodes {
        const val ERROR = 0x00
        const val STARTUP = 0x01
        const val READY = 0x02
        const val AUTHENTICATE = 0x03
        const val OPTIONS = 0x05
        const val SUPPORTED = 0x06
        const val QUERY = 0x07
        const val RESULT = 0x08
        const val PREPARE = 0x09
        const val EXECUTE = 0x0A
        const val REGISTER = 0x0B
        const val EVENT = 0x0C
        const val BATCH = 0x0D
        const val AUTH_CHALLENGE = 0x0E
        const val AUTH_RESPONSE = 0x0F
        const val AUTH_SUCCESS = 0x10
    }

    enum class Consistency(val value: Int) {
        ANY(0x0000),
        ONE(0x0001),
        TWO(0x0002),
        THREE(0x0003),
        QUORUM(0x0004),
        ALL(0x0005),
        LOCAL_QUORUM(0x0006),
        EACH_QUORUM(0x0007),
        SERIAL(0x0008),
        LOCAL_SERIAL(0x0009),
        LOCAL_ONE(0x000A),
    }

    interface ColumnType<T> {
        fun interpret(data: ByteArray): T

        data class CUSTOM(val value: String) : ColumnType<ByteArray> {
            override fun interpret(data: ByteArray) = data
        }

        object ASCII : ColumnType<ByteArray> {
            override fun interpret(data: ByteArray) = data
        }

        object BIGINT : ColumnType<Long> {
            override fun interpret(data: ByteArray) = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getLong(0)
        }

        object BLOB : ColumnType<ByteArray> {
            override fun interpret(data: ByteArray) = data
        }

        object BOOLEAN : ColumnType<ByteArray> {
            override fun interpret(data: ByteArray) = data
        }

        object COUNTER : ColumnType<ByteArray> {
            override fun interpret(data: ByteArray) = data
        }

        object DECIMAL : ColumnType<ByteArray> {
            override fun interpret(data: ByteArray) = data
        }

        object DOUBLE : ColumnType<ByteArray> {
            override fun interpret(data: ByteArray) = data
        }

        object FLOAT : ColumnType<ByteArray> {
            override fun interpret(data: ByteArray) = data
        }

        object INT : ColumnType<ByteArray> {
            override fun interpret(data: ByteArray) = data
        }

        object TIMESTAMP : ColumnType<ByteArray> {
            override fun interpret(data: ByteArray) = data
        }

        object UUID : ColumnType<ByteArray> {
            override fun interpret(data: ByteArray) = data
        }

        object VARCHAR : ColumnType<String> {
            override fun interpret(data: ByteArray) = data.toString(Charsets.UTF_8)
        }

        object VARINT : ColumnType<ByteArray> {
            override fun interpret(data: ByteArray) = data
        }

        object TIMEUUID : ColumnType<ByteArray> {
            override fun interpret(data: ByteArray) = data
        }

        object INET : ColumnType<ByteArray> {
            override fun interpret(data: ByteArray) = data
        }

        data class LIST(val element: ColumnType<*>) :
            ColumnType<Any?> {
            override fun interpret(data: ByteArray) = data
        }

        data class MAP(val key: ColumnType<*>, val value: ColumnType<*>) :
            ColumnType<Any?> {
            override fun interpret(data: ByteArray) = data
        }

        data class SET(val element: ColumnType<*>) :
            ColumnType<Any?> {
            override fun interpret(data: ByteArray) = data
        }

        companion object {
            fun read(s: ByteArrayInputStream): ColumnType<*> {
                val kind = s.readS16_be()
                return when (kind) {
                    0x0000 -> CUSTOM(s.readCassandraString())
                    0x0001 -> ASCII
                    0x0002 -> BIGINT
                    0x0003 -> BLOB
                    0x0004 -> BOOLEAN
                    0x0005 -> COUNTER
                    0x0006 -> DECIMAL
                    0x0007 -> DOUBLE
                    0x0008 -> FLOAT
                    0x0009 -> INT
                    0x000B -> TIMESTAMP
                    0x000C -> UUID
                    0x000D -> VARCHAR
                    0x000E -> VARINT
                    0x000F -> TIMEUUID
                    0x0010 -> INET
                    0x0020 -> LIST(
                        read(
                            s
                        )
                    )
                    0x0021 -> MAP(
                        read(
                            s
                        ), read(s)
                    )
                    0x0022 -> SET(
                        read(
                            s
                        )
                    )
                    0x0030 -> TODO("UDT NOT IMPLEMENTED")
                    0x0031 -> TODO("TUPLE NOT IMPLEMENTED")
                    else -> TODO("Unsupported $kind")
                }
            }
        }
    }

    data class Column(
        val index: Int,
        val ksname: String,
        val tablename: String,
        val name: String,
        val type: ColumnType<*>
    ) {
        fun interpret(data: ByteArray): Any? = type.interpret(data)
    }

    data class Columns(val columns: List<Column>) : List<Column> by columns {
        fun contains(name: String): Boolean = columns.any { it.name == name }

        fun getIndex(name: String): Int {
            val index = columns.indexOfFirst { it.name == name }
            if (index < 0) throw IllegalArgumentException("Can't find column '$name'")
            return index
        }
    }

    data class Row(
        val columns: Columns,
        val data: List<ByteArray>
    ) : Map<String, Any?> {
        data class MyEntry(override val key: String, override val value: Any?) : Map.Entry<String, Any?>

        override val size: Int = data.size
        override val keys: Set<String> by lazy { columns.columns.map { it.name }.toSet() }
        override val values: List<Any?> by lazy { (0 until size).map { this[it] } }
        override val entries: Set<Map.Entry<String, Any?>> by lazy {
            (0 until size).map {
                MyEntry(
                    columns[it].name,
                    values[it]
                )
            }.toSet()
        }

        override fun containsKey(key: String): Boolean = keys.contains(key)
        override fun containsValue(value: Any?): Boolean = values.contains(value)

        override fun isEmpty(): Boolean = size == 0

        fun getColumn(name: String) = columns[columns.getIndex(name)]
        fun getColumnIndex(name: String) = columns.getIndex(name)

        operator fun get(index: Int) = columns[index].interpret(data[index])
        fun getRaw(index: Int) = data[index]
        fun getString(index: Int) = getRaw(index).toString(Charsets.UTF_8)

        override operator fun get(key: String) = get(getColumnIndex(key))
        fun getRaw(key: String) = getRaw(getColumnIndex(key))
        fun getString(key: String) = getString(getColumnIndex(key))

        override fun toString(): String =
            columns.columns.map { "${it.name}=${it.interpret(data[it.index])}" }.joinToString(", ")
    }

    data class Rows(
        val columns: Columns,
        val rows: List<Row>
    ) : Collection<Row> by rows {
        operator fun get(index: Int) = rows[index]
    }

    data class Packet(
        val version: Int = 3,
        val flags: Int = 0,
        val stream: Int = 0,
        val opcode: Int,
        val payload: Bytes
    ) {
        companion object {
            suspend fun read(reader: ByteReadChannel): Packet {
                val info = reader.readBytesExact(9).openSync()
                val version = info.readU8()
                val flags = info.readU8()
                val stream = info.readU16_be()
                val opcode = info.readU8()
                val length = info.readS32_be()
                val payload = reader.readBytesExact(length)
                return Packet(
                    version,
                    flags,
                    stream,
                    opcode,
                    payload.toBytes()
                )
            }
        }

        fun toByteArray(): ByteArray = MemorySyncStreamToByteArray {
            write8(version)
            write8(flags)
            write16_be(stream)
            write8(opcode)
            write32_be(payload.bytes.size)
            writeBytes(payload.bytes)
        }
    }

    companion object {
        suspend operator fun invoke(host: String = "127.0.0.1", port: Int = 9042, debug: Boolean = false): Cassandra {
            val bufferSize = 0x1000
            val client = aSocket(ActorSelectorManager(ioCoroutineDispatcher)).tcp().connect(InetSocketAddress(host, port))
            return invoke(
                reader = client.openReadChannel(),
                writer = client.openWriteChannel(autoFlush = true),
                close = client,
                bufferSize = bufferSize,
                debug = debug
            )
        }

        /**
         * Constructor used for unittesting
         */
        suspend operator fun invoke(
            reader: ByteReadChannel,
            writer: ByteWriteChannel,
            close: Closeable,
            bufferSize: Int = 0x1000,
            debug: Boolean = false
        ): Cassandra {
            val cc = Cassandra(reader, writer, close, bufferSize, debug)
            launch {
                try {
                    cc.init()
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }.start()
            cc.readyDeferred.await()
            return cc
        }
    }

    private val readyDeferred = CompletableDeferred<Unit>()
    val ready = readyDeferred

    data class Channel(val id: Int) {
        val data = ProduceConsumer<Packet>()
    }

    val channels = ArrayList<Channel>()
    private val availableChannels = AsyncPool(factory = ObjectFactory {
        Channel(channels.size).apply { channels += this }
    })

    suspend fun init() {
        availableChannels.borrow()
        sendStartup()
        while (true) {
            val packet = Packet.read(reader)
            log("RECV packet[${packet.stream}]: $packet")
            // stream 0 used here for internal usage, while negative ones are from server
            if (packet.stream <= 0) {
                try {
                    when (packet.opcode) {
                        Opcodes.READY -> {
                            readyDeferred.complete(Unit)
                        }
                        else -> {
                            TODO("Unsupported root opcode ${packet.opcode}")
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            } else {
                val channel = channels.getOrNull(packet.stream)
                channel?.data?.produce(packet)
            }
        }
    }

    private suspend fun <T> allocStream(callback: suspend (Channel) -> T): T = availableChannels.use { callback(it) }

    suspend fun createKeyspace(
        namespace: String,
        ifNotExists: Boolean = true,
        dc1: Int = 1,
        dc2: Int = 3,
        durableWrites: Boolean = false
    ) {
        val ifNotExistsStr = if (ifNotExists) " IF NOT EXISTS" else ""
        // @TODO: Use parameters instead of $name
        query("CREATE KEYSPACE$ifNotExistsStr $namespace WITH replication = {'class': 'NetworkTopologyStrategy', 'DC1' : $dc1, 'DC2' : $dc2} AND durable_writes = $durableWrites;")
    }

    // @TODO: Use parameters $namespace
    suspend fun use(namespace: String): String = query("USE $namespace;")[0].getString(0)

    suspend fun useOrCreate(namespace: String): String {
        createKeyspace(namespace, ifNotExists = true)
        return use(namespace)
    }

    suspend fun query(@Language("cql") query: String, consistency: Consistency = Consistency.ONE): Rows {
        ready.await()
        return allocStream { channel ->
            writePacket(
                Packet(
                    opcode = Opcodes.QUERY,
                    stream = channel.id,
                    payload = MemorySyncStreamToByteArray {
                        val flags = 0
                        writeCassandraLongString(query)
                        write16_be(consistency.value)
                        write8(flags)
                    }.toBytes()
                )
            )
            val result = channel.data.consume()
            val res = if (result != null) interpretPacket(result) else Unit
            when (res) {
                is Rows -> res
                is String -> {
                    val columns = Columns(
                        listOf(
                            Column(
                                0,
                                "",
                                "",
                                "value",
                                ColumnType.VARCHAR
                            )
                        )
                    )
                    Rows(
                        columns,
                        listOf(
                            Row(
                                columns,
                                listOf(res.toByteArray(Charsets.UTF_8))
                            )
                        )
                    )
                }
                else -> {
                    Rows(
                        Columns(
                            listOf()
                        ), listOf()
                    )
                }
            }
        }
    }

    data class CassandraException(val errorCode: Int, val errorMessage: String) : Exception(errorMessage)

    private fun interpretPacket(packet: Packet): Any {
        val payload = packet.payload
        when (packet.opcode) {
            Opcodes.ERROR -> {
                val s = payload.bytes.openSync()
                val errorCode = s.readS32_be()
                val errorMessage = s.readCassandraString()
                throw CassandraException(errorCode, errorMessage)
            }
            Opcodes.RESULT -> {
                val s = payload.bytes.openSync()
                val kind = s.readS32_be()
                //println("result: $kind")
                when (kind) {
                    0x0001 -> { // Void
                        // No info
                        return Unit
                    }
                    0x0002 -> { // Rows
                        val flags = s.readS32_be()
                        val columns_count = s.readS32_be()

                        val Global_tables_spec = (flags and 0b001) != 0
                        val Has_more_pages = (flags and 0b010) != 0
                        val No_metadata = (flags and 0b100) != 0

                        val global_table_spec =
                            if (Global_tables_spec) Pair(s.readCassandraString(), s.readCassandraString()) else null

                        val columnsList = ArrayList<Column>()
                        for (n in 0 until columns_count) {
                            val spec = global_table_spec ?: Pair(s.readCassandraString(), s.readCassandraString())
                            val name = s.readCassandraString()
                            val type = s.readCassandraColumnType()
                            //println("SPEC: $spec, $name, $type")
                            columnsList += Column(
                                columnsList.size,
                                spec.first,
                                spec.second,
                                name,
                                type
                            )
                        }

                        val columns = Columns(columnsList)
                        val rowsList = ArrayList<Row>()
                        val rows_count = s.readS32_be()
                        for (n in 0 until rows_count) {
                            val cells = ArrayList<ByteArray>()
                            for (m in 0 until columns_count) {
                                cells += s.readCassandraBytes()
                            }
                            rowsList += Row(columns, cells)
                        }
                        val rows = Rows(columns, rowsList)
                        //println(rows)

                        //println(rows.size)
                        //println(rows[0].getString("name"))
                        //println(rows[1].getString("name"))

                        return rows
                    }
                    0x0003 -> { // Set_keyspace
                        val keyspace = s.readCassandraString()
                        //println(keyspace)

                        return keyspace
                    }
                    0x0004 -> { // Prepared
                        return Unit
                    }
                    0x0005 -> { // Schema_change
                        return Unit
                    }
                    else -> TODO("Unsupported result type $kind")
                }
            }
            else -> TODO("Unsupported package ${packet.opcode}")
        }
    }

    private val writeQueue = AsyncQueue()

    private suspend fun sendStartup() {
        writePacket(
            Packet(
                opcode = Opcodes.STARTUP,
                stream = 0,
                payload = MemorySyncStreamToByteArray {
                    writeCassandraStringMap(
                        linkedMapOf(
                            "CQL_VERSION" to "3.0.0"
                            //"COMPRESSION"
                        )
                    )
                }.toBytes()
            )
        )
    }

    suspend fun writePacket(p: Packet) {
        writeQueue {
            log("SEND: $p")
            writer.writeFully(p.toByteArray())
        }
    }


    private fun log(msg: String) {
        if (debug) println(msg)
    }

    suspend fun close() {
        writeQueue {
            close.close()
        }
    }
}

private fun ByteArrayInputStream.readCassandraColumnType(): Cassandra.ColumnType<*> =
    Cassandra.ColumnType.read(this)

private fun ByteArrayInputStream.readCassandraString(): String = this.readString(this.readS16_be(), Charsets.UTF_8)
private fun ByteArrayInputStream.readCassandraLongString(): String = this.readString(this.readS32_be(), Charsets.UTF_8)
private fun ByteArrayInputStream.readCassandraShortBytes(): ByteArray = this.readBytesExact(this.readS16_be())
private fun ByteArrayInputStream.readCassandraBytes(): ByteArray = this.readBytesExact(this.readS32_be())

private fun ByteArrayOutputStream.writeCassandraString(str: String) {
    val data = str.toByteArray(Charsets.UTF_8)
    this.write16_be(data.size)
    this.writeBytes(data)
}

private fun ByteArrayOutputStream.writeCassandraLongString(str: String) {
    val data = str.toByteArray(Charsets.UTF_8)
    this.write32_be(data.size)
    this.writeBytes(data)
}

private fun ByteArrayOutputStream.writeCassandraStringList(strs: List<String>) {
    this.write16_be(strs.size)
    for (str in strs) writeCassandraString(str)
}

private fun ByteArrayOutputStream.writeCassandraStringMap(map: Map<String, String>) {
    this.write16_be(map.size)
    for ((k, v) in map) {
        this.writeCassandraString(k)
        this.writeCassandraString(v)
    }
}

private fun ByteArrayOutputStream.writeCassandraStringMultiMap(map: Map<String, List<String>>) {
    this.write16_be(map.size)
    for ((k, v) in map) {
        this.writeCassandraString(k)
        this.writeCassandraStringList(v)
    }
}

class Bytes(val bytes: ByteArray) {
    val size get() = bytes.size
}

fun ByteArray.toBytes() = Bytes(this)

