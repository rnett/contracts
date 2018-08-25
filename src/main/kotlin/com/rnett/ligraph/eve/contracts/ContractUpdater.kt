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
import kotlinx.coroutines.experimental.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.postgresql.core.Utils

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

    fun updateContractsForRegion(regionID: Int = 10000002 /*The Forge*/): Pair<Int, Int> {
        removeExpired() //TODO remove contracts not found in query

        val client = HttpClient(Apache)
        val parser = JsonParser()

        var url: String
        var page = 1

        var text: String

        val contracts = mutableListOf<JsonElement>()

        do {
            url = "https://esi.evetech.net/latest/contracts/public/$regionID/?datasource=tranquility&page=$page"
            text = runBlocking {
                client.get<String>(url) {
                    headers["accept"] = "application/json"
                }
            }

            if (text != "[]")
                contracts.addAll(parser.parse(text).asJsonArray.toList())

            page++
        } while (text != "[]" && text != "")

        var updated = 0
        var skipped = 0

        contracts.forEach {
            val contract = Gson().fromJson<GsonContract>(it)
            if (writeContract(contract))
                updated++
            else
                skipped++
        }
        return Pair(updated, skipped)
    }

    fun removeExpired() {
        transaction {
            //TODO better way of doing this.  need to use now in postgres
            Contract.all().filter { it.expired }.forEach { it.delete() }
        }
    }

    private fun writeContract(contract: GsonContract): Boolean {

        if (transaction { Contract.findById(contract.contractId) } != null)
            return false

        contract.apply {
            transaction {

                val escapedTitle = StringBuilder()

                Utils.escapeLiteral(escapedTitle, title, true)

                TransactionManager.current().exec("""INSERT INTO contracts VALUES
                    |($contractId, $collateral, '$rawDateIssued', '$rawDateExpired', $daysToComplete, $endLocationId,
                    |$forCorporation, $issuerCorporationId, $issuerId, $price, $reward, $startLocationId,
                    |'$escapedTitle', '$rawType', $volume);""".trimMargin())
            }
        }
        if (contract.type.hasItems)
            writeItems(contract.contractId)

        return true
    }

    private fun writeItems(contractId: Int) {
        val client = HttpClient(Apache)

        var url = "https://esi.evetech.net/latest/contracts/public/items/$contractId/?datasource=tranquility&page=1"
        val text: String = runBlocking {
            client.get<String>(url) {
                headers["accept"] = "application/json"
            }
        }

        url = "https://esi.evetech.net/latest/contracts/public/items/$contractId/?datasource=tranquility&page=2"
        val text2: String = runBlocking {
            client.get<String>(url) {
                headers["accept"] = "application/json"
            }
        }

        var items = JsonParser().parse(text).asJsonArray.toList()
        if (text2 != "[]") {
            try {
                items = items + JsonParser().parse(text2).asJsonArray.toList()
            } catch (e: Exception) {

            }
        }

        transaction {
            items.map { it.asJsonObject }.forEach {

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

                TransactionManager.current().exec("""INSERT INTO contractitems VALUES (
                    |$contractId, ${it["item_id"]?.nullInt ?: 0}, ${it["type_id"].asInt}, ${it["quantity"].asInt},
                    |$me, $te, $runs, '$bpType'
                |);""".trimMargin())
            }
        }

    }
}
