package com.rnett.ligraph.eve.contracts

import com.github.salomonbrys.kotson.fromJson
import com.github.salomonbrys.kotson.nullBool
import com.github.salomonbrys.kotson.nullInt
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kizitonwose.time.Interval
import com.kizitonwose.time.milliseconds
import com.kizitonwose.time.minutes
import com.rnett.core.Cache
import com.rnett.eve.ligraph.sde.invtype
import com.rnett.eve.ligraph.sde.invtypes
import com.rnett.eve.ligraph.sde.invtypes.published
import com.rnett.eve.ligraph.sde.invtypes.typeName
import com.rnett.ligraph.eve.contracts.blueprints.BPType
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.response.HttpResponse
import io.ktor.client.response.readText
import kotlinx.coroutines.experimental.*
import org.apache.commons.io.output.TeeOutputStream
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.postgresql.core.Utils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.util.*


object UpdaterMain {
    @JvmStatic
    fun main(args: Array<String>) {

        val logFile = File("./contracts_log.txt")

        val fileStream = FileOutputStream(logFile, true)
        val stringStream = ByteArrayOutputStream()

        System.setOut(PrintStream(TeeOutputStream(TeeOutputStream(fileStream, stringStream), System.out)))
        System.setErr(PrintStream(TeeOutputStream(TeeOutputStream(fileStream, stringStream), System.err)))

        DBConnection.connect()

        val log = UpdateLog.makeDefault()

        try {
            ContractUpdater.updateContractsForRegion(updateLog = log)
            println("\n\n")

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            transaction { log.log = stringStream.toString() }
        }
        transaction { log.log = stringStream.toString() }
    }
}

object ContractUpdater {

    val regions = mutableListOf<Int>()

    var updateInterval: Interval<*> = 30.minutes // cache time

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
            delay(updateInterval.inMilliseconds.longValue)
        }
        //println("Stopped")
        _running = false
    }

    fun updateContractsForRegion(regionID: Int = 10000002 /*The Forge*/, updateLog: UpdateLog = UpdateLog.makeDefault()) {

        transaction { updateLog.region = regionID }

        println("[${DateTime.now()}] Starting Contracts")

        val start = Calendar.getInstance().timeInMillis.milliseconds

        val newContracts = getArrayFromPages({ "https://esi.evetech.net/latest/contracts/public/$regionID/?datasource=tranquility&page=$it" })
                .asSequence()
                .map { Gson().fromJson<GsonContract>(it) }

        println("[${DateTime.now()}] ESI Query Done")

        if (newContracts.count() == 0) { // hit etag
            transaction {
                updateLog.items = 0
                updateLog.completed = true
                updateLog.duration = Calendar.getInstance().timeInMillis.milliseconds - start
            }
            return
        }

        val newIds = newContracts.asSequence().map { it.contractId }.toSet()

        val have = transaction { contracts.slice(contracts.contractId).selectAll().map { it[contracts.contractId] }.toSet() }

        val toRemove = have.filter { !newIds.contains(it) }
        transaction { contracts.deleteWhere { contracts.contractId inList toRemove } }
        println("[${DateTime.now()}] Deletion Done")

        val toAdd = newContracts.filter { !have.contains(it.contractId) }

        transaction { updateLog.contracts = toAdd.count() }

        if (toAdd.count() == 0) {
            println("Hit ESI e-tag Cache")
            //TODO re-run in 5 mins or so (or wait?)
            transaction {
                updateLog.items = 0
                updateLog.duration = Calendar.getInstance().timeInMillis.milliseconds - start
            }
            return
        }

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

        val jobs = mutableListOf<Job>()

        val itemList = toAdd.asSequence().filter { it.type == ContractType.ItemExchange }.map { async { writeItems(it.contractId) } }.toList()

        val items = runBlocking { itemList.awaitAll() }
                .asSequence().map { (contractId, json) -> json.map { Pair(contractId, it) } }.reduce { a, b -> a + b }

        transaction {
            updateLog.items = items.count()
        }

        println("[${DateTime.now()}] Items Done")

        println("[${DateTime.now()}] Starting Mutated Items")

        val mutated = items.asSequence().filter {
            it.second["item_id"]?.nullInt != null && it.second["type_id"]?.nullInt != null &&
                    it.second["is_included"].nullBool == true && abyssalTypes.contains(it.second["type_id"].asInt)
        }

        runBlocking {
            mutated.map { launch { writeMutatedItem(it.first, it.second["item_id"].asLong, it.second["type_id"].asInt) } }.toList().joinAll()
        }

        transaction {
            updateLog.mutatedItems = mutated.count()
        }

        println("[${DateTime.now()}] Mutated Items Done")

        transaction { TransactionManager.current().exec("TRUNCATE appraisalcache;") }

        transaction {
            updateLog.completed = true

            updateLog.duration = Calendar.getInstance().timeInMillis.milliseconds - start
        }
    }


    val abyssalTypes = transaction { invtype.wrapRows(invtypes.select { typeName like "%Abyssal%" and (published eq true) }).map { it.typeID }.toSet() }

    private fun writeItems(contractId: Int): Pair<Int, List<JsonObject>> {
        val items = getArrayFromPages({ "https://esi.evetech.net/latest/contracts/public/items/$contractId/?datasource=tranquility&page=$it" }, useETag = false).map { it.asJsonObject }

        if (items.count() == 0)
            return Pair(contractId, items)

        if (items.count { it["is_included"].nullBool == false } > 0) {
            transaction {
                Contract.findById(contractId)?.setType(ContractType.Trade)
            }
        }

        val insertQuery = items.joinToString(", ", "INSERT INTO contractitems VALUES ", ";") {

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

            val required = (it["is_included"].nullBool == false)

            "($contractId, ${it["item_id"]?.nullInt ?: 0}, ${it["type_id"].asInt}, ${it["quantity"].asInt}, " +
                    "$me, $te, $runs, '$bpType', $required)"
        }
        try {
            transaction {
                TransactionManager.current().exec(insertQuery)
            }

        } catch (e: Exception) {
        }

        return Pair(contractId, items)
    }

    private val attributeCache = Cache<invtype, Map<Int, Double>> {
        transaction {
            it.invtype_dgmtypeattributes_type.toList().map {
                Pair(it.attributeID, it.valueFloat?.toDouble() ?: it.valueInt?.toDouble() ?: 0.0)
            }
        }.toMap()
    }

    private fun writeMutatedItem(contractId: Int, itemId: Long, typeId: Int) {

        val text = esiStringCall("https://esi.evetech.net/latest/dogma/dynamic/items/$typeId/$itemId/?datasource=tranquility")

        val json = parser.parse(text).asJsonObject

        val item = transaction {
            MutatedItem.new(itemId) {
                _contractId = contractId
                _typeId = typeId
                _baseTypeId = json["source_type_id"].asInt
                _mutatorTypeId = json["mutator_type_id"].asInt
            }
        }

        val baseAttributes = attributeCache[transaction { item.baseType }]

        val changedAttributes = json["dogma_attributes"].asJsonArray.asSequence().map { it.asJsonObject }.map { Pair(it["attribute_id"].asInt, it["value"].asDouble) }
                .filter { baseAttributes.containsKey(it.first) && baseAttributes[it.first] != it.second }

        if (changedAttributes.count() > 0) {

            val insertQuery = changedAttributes.joinToString(", ", "INSERT INTO mutatedattributes VALUES ", ";")
            { "($itemId, ${it.first}, ${baseAttributes[it.first]!!}, ${it.second}, ${100 * (it.second - baseAttributes[it.first]!!) / baseAttributes[it.first]!!}, $typeId)" }


            try {
                transaction {
                    TransactionManager.current().exec(insertQuery)
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

    }
}

val client = HttpClient(Apache)
val parser = JsonParser()

internal fun getArrayFromPages(url: (page: Int) -> String, startPage: Int = 1, useETag: Boolean = true): List<JsonElement> {


    val etag: String = if (useETag) {

        transaction {
            TransactionManager.current().exec("SELECT etag FROM contractetags WHERE url = '${url(startPage)}'") {
                if (it.next())
                    it.getString("etag")
                else
                    ""
            }
        } ?: ""

    } else
        ""

    val response = esiCall<HttpResponse>(url(startPage)) {
        if (useETag && etag != "")
            header("If-None-Match", etag)
    }


    if (response.status.value == 304) { // no changes
        return emptyList()
    }

    if (useETag) {
        val newEtag = response.headers["ETag"]

        if (newEtag != null) {
            transaction {
                TransactionManager.current().exec("DELETE FROM contractetags WHERE url = '${url(startPage)}'")
                TransactionManager.current().exec("INSERT INTO contractetags VALUES ('${url(startPage)}', '$newEtag')")
            }
        }
    }

    val pages = response.headers["x-pages"]?.toInt() ?: return try {
        parser.parse(runBlocking { response.readText() }).asJsonArray.toList()
    } catch (e: Exception) {
        emptyList()
    }

    val list: MutableList<JsonElement>

    list = try {
        parser.parse(runBlocking { response.readText() }).asJsonArray.toMutableList()
    } catch (e: Exception) {
        runBlocking { delay(100) }
        try {
            parser.parse(runBlocking { response.readText() }).asJsonArray.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    for (i in (startPage + 1)..pages) {
        val text = esiStringCall(url(i))
        list.addAll(parser.parse(text).asJsonArray.toList())
    }

    return list
}

inline fun <reified T> esiCall(url: String, crossinline builder: HttpRequestBuilder.() -> Unit = {}) = runBlocking {
    client.get<T>(url) {
        header("User-Agent", "Ligraph / JNett96@gnail.com")
        builder()
    }
}

fun esiStringCall(url: String, builder: HttpRequestBuilder.() -> Unit = {}): String = esiCall(url, builder)