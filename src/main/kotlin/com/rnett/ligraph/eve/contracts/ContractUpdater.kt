package com.rnett.ligraph.eve.contracts

import com.github.salomonbrys.kotson.fromJson
import com.github.salomonbrys.kotson.nullInt
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.rnett.ligraph.eve.contracts.blueprints.BPType
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.request.get
import io.ktor.client.response.HttpResponse
import io.ktor.client.response.readText
import kotlinx.coroutines.experimental.*
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.postgresql.core.Utils

//TODO use e-tag (send, get 304 if no changes)
object ContractUpdater {

    val regions = mutableListOf<Int>()

    var updateIntervalSeconds: Int = 60 * 5

    private lateinit var updateJob: Job

    private var stop = false
    private var _running = false

    val running get() = _running

    fun start() {
        launch {
            forceStop()
            runBlocking { delay(100) }

            stop = false


            updateJob = getJob()
        }
    }

    fun stop() {

        if (!running)
            return

        stop = true
    }

    fun forceStop() {

        if (!running)
            return

        stop = true

        runBlocking {
            joinAll(updateJob)
        }
    }

    private fun getJob() = launch {
        //TODO some form of logging
        _running = true
        //println("Started")
        while (!stop) {
            //println("Update Started")
            for (region in regions) {
                if (stop)
                    break
                ContractUpdater.updateContractsForRegion(region)
            }
            //println("Update Done")
            delay(1000 * updateIntervalSeconds)
        }
        //println("Stopped")
        _running = false
    }

    //TODO use pagination using response header properly.  x-pages in the header will contain the # of pages
    fun updateContractsForRegion(regionID: Int = 10000002 /*The Forge*/): Pair<Int, Int> {

        println("[${DateTime.now()}] Starting Contracts")

        val newContracts = getArrayFromPages({ "https://esi.evetech.net/latest/contracts/public/$regionID/?datasource=tranquility&page=$it" }).map { Gson().fromJson<GsonContract>(it) }

        println("[${DateTime.now()}] ESI Query Done")

        val newIds = newContracts.map { it.contractId }.toSet()

        val have = transaction { contracts.slice(contracts.contractId).selectAll().map { it[contracts.contractId] }.toSet() }

        val toRemove = have.filter { !newIds.contains(it) }
        transaction { contracts.deleteWhere { contracts.contractId inList toRemove } }

        println("[${DateTime.now()}] Deletion Done")

        val toAdd = newContracts.filter { !have.contains(it.contractId) }

        if (toAdd.count() == 0)
            return Pair(0, newContracts.count())

        val insertQuery = toAdd.joinToString(", ", "INSERT INTO contracts VALUES ", ";") {
            it.run {
                val escapedTitle = StringBuilder()

                Utils.escapeLiteral(escapedTitle, title, true)

                "($contractId, $collateral, '$rawDateIssued', '$rawDateExpired', $daysToComplete, $endLocationId, " +
                        "$forCorporation, $issuerCorporationId, $issuerId, $price, $reward, $startLocationId, " +
                        "'$escapedTitle', '$rawType', $volume)"
            }
        }

        println("[${DateTime.now()}] Contracts Query Built")

        transaction {
            TransactionManager.current().exec(insertQuery)
        }

        println("[${DateTime.now()}] Contracts Done")

        println("[${DateTime.now()}] Starting Items")
        toAdd.filter { it.type == ContractType.ItemExchange }.forEach { writeItems(it.contractId) }
        println("[${DateTime.now()}] Items Done")

        return Pair(toAdd.count(), newContracts.count() - toAdd.count())
    }

    private fun writeItems(contractId: Int) {
        val items = getArrayFromPages({ "https://esi.evetech.net/latest/contracts/public/items/$contractId/?datasource=tranquility&page=$it" })

        val insertQuery = items.map { it.asJsonObject }.joinToString(", ", "INSERT INTO contractitems VALUES ", ";") {

            val me = if (it.has("material_efficiency")) it["material_efficiency"].nullInt?.toString()
                    ?: "null" else "null"

            val te = if (it.has("time_efficiency")) it["time_efficiency"].nullInt?.toString() ?: "null" else "null"

            var runs = if (it.has("runs")) it["runs"].nullInt?.toString() ?: "null" else "null"

            val bp = me != "null"

            val bpType = when {
                me == "null" -> BPType.NotBP
                runs == "null" -> BPType.BPO
                runs != "null" -> BPType.BPC
                else -> BPType.NotBP
            }

            if (bpType == BPType.BPO)
                runs = "0"

            "($contractId, ${it["item_id"]?.nullInt ?: 0}, ${it["type_id"].asInt}, ${it["quantity"].asInt}, " +
                    "$me, $te, $runs, '$bpType')"
        }
        try {
            transaction {
                TransactionManager.current().exec(insertQuery)
            }
        } catch (e: Exception) {
        }
    }
}


internal fun getArrayFromPages(url: (page: Int) -> String, startPage: Int = 1): List<JsonElement> {
    val client = HttpClient(Apache)
    val parser = JsonParser()

    val response = runBlocking { client.get<HttpResponse>(url(startPage)) }
    val pages = response.headers.get("x-pages")?.toInt()

    if (pages == null)
        return try {
            parser.parse(runBlocking { response.readText() }).asJsonArray.toList()
        } catch (e: Exception) {
            emptyList()
        }

    var list: MutableList<JsonElement>

    list = try {
        parser.parse(runBlocking { response.readText() }).asJsonArray.toMutableList()
    } catch (e: Exception) {
        runBlocking {
            delay(100)
        }
        try {
            parser.parse(runBlocking { response.readText() }).asJsonArray.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    for (i in (startPage + 1)..pages) {
        val text = runBlocking { client.get<String>(url(i)) }
        list.addAll(parser.parse(text).asJsonArray.toList())
    }

    return list
}