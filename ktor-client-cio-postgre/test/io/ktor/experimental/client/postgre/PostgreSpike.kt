package io.ktor.experimental.client.postgre

import kotlinx.coroutines.experimental.*

object PostgreSpike {
    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking {
            val client = PostgreClient(
                user = "ktor-cio-sample",
                database = "ktor-cio-sample"
            )
            for (row in client.query("SELECT now();")) {
                //for (row in client.query("SELECT 1;")) {
                println(row)
                for (cell in row) {
                    println(cell)
                }
                println(row.string(0))
                println(row.string("now"))
                println(row.columns)
                println(row.cells.size)
                println(row.cells.map { it?.size })
                println(row.cells.map { it?.toString(Charsets.UTF_8) })
            }
        }
    }
}
