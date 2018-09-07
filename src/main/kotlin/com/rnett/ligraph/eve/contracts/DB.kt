package com.rnett.ligraph.eve.contracts

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.kizitonwose.time.Interval
import com.kizitonwose.time.Millisecond
import com.kizitonwose.time.milliseconds
import com.rnett.eve.ligraph.sde.*
import com.rnett.ligraph.eve.contracts.blueprints.BPC
import com.rnett.ligraph.eve.contracts.blueprints.BPO
import com.rnett.ligraph.eve.contracts.blueprints.BPType
import com.rnett.ligraph.eve.contracts.blueprints.Blueprint
import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.util.*

object contracts : IntIdTable(columnName = "contractid") {
    val contractId = integer("contractid").primaryKey()
    val collateral = integer("collateral")
    val rawDateIssued = varchar("dateissued", 40)
    val rawDateExpired = varchar("dateexpired", 40)
    val daysToComplete = integer("daystocomplete")
    val endLocationId = integer("endlocationid")
    val forCorporation = bool("forcorporation")
    val issuerCorporationId = integer("issuercorporationid")
    val issuerId = integer("issuerid")
    val rawPrice = decimal("price", 50, 50)
    val rawReward = decimal("reward", 50, 50)
    val startLocationId = integer("startlocationid")
    val title = varchar("title", 100)
    val rawType = varchar("type", 50)
    val rawVolume = decimal("volume", 50, 50)
}

class ContractAdapter : TypeAdapter<Contract>() {
    override fun read(input: JsonReader): Contract? {
        input.beginObject()
        input.nextName()
        val c = transaction { Contract.findById(input.nextInt()) }
        input.endObject()

        return c
    }

    override fun write(out: JsonWriter, value: Contract) {
        out.beginObject()
        out.name("contractId").value(value.id.value)
        out.endObject()
    }
}

@JsonAdapter(ContractAdapter::class)
class Contract(id: EntityID<Int>) : IntEntity(id), IContract {
    companion object : IntEntityClass<Contract>(contracts)

    override val collateral by contracts.collateral
    override val contractId by contracts.contractId
    val rawDateIssued by contracts.rawDateIssued
    val rawDateExpired by contracts.rawDateExpired
    override val daysToComplete by contracts.daysToComplete
    override val endLocationId by contracts.endLocationId
    override val forCorporation by contracts.forCorporation
    override val issuerCorporationId by contracts.issuerCorporationId
    override val issuerId by contracts.issuerId
    val rawPrice by contracts.rawPrice
    val rawReward by contracts.rawReward
    override val startLocationId by contracts.startLocationId
    override val title by contracts.title
    private var _rawType by contracts.rawType
    val rawType get() = _rawType
    val rawVolume by contracts.rawVolume

    override val type: ContractType by lazy { ContractType.fromRaw(rawType) }

    override val dateExpired by lazy { DateTime(rawDateExpired) }
    override val dateIssued by lazy { DateTime(rawDateIssued) }

    override val price by lazy { rawPrice.toDouble() }
    override val reward by lazy { rawReward.toDouble() }
    override val volume by lazy { rawVolume.toDouble() }

    private val _items by ContractItem referrersOn contractitems.contract

    val items by lazy { transaction { _items.filter { !it.required } } }

    val requiredItems by lazy { transaction { _items.filter { it.required } } }

    val blueprints by lazy {
        transaction {
        items.filter { it.bpType != BPType.NotBP }.map {
            if (it.bpType == BPType.BPC) {
                BPC(it.type, it.runs!!, it.me ?: 0, it.te ?: 0)
            } else {
                BPO(it.type, it.me ?: 0, it.te ?: 0)
            }
        }.toList()
        }
    }

    val bpcs by lazy { blueprints.map { it.asBpc() }.filterNotNull() }
    val bpos by lazy { blueprints.map { it.asBpo() }.filterNotNull() }

    override fun toString(): String {
        return title + " : $price ISK [$type]"
    }

    override fun hashCode(): Int {
        return contractId
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Contract)
            return false

        return other.contractId == contractId
    }

    internal fun setType(type: ContractType) {
        _rawType = type.raw
    }
}

object contractitems : IntIdTable(columnName = "contractid\" << 8 | \"itemid") {

    val contractId = integer("contractid")
    val itemId = integer("itemid")
    val typeId = integer("typeid")
    val quantity = integer("quantity")
    val me = integer("me").nullable()
    val te = integer("te").nullable()
    val runs = integer("runs").nullable()
    val rawBpType = varchar("bptype", 20)
    val required = bool("required")

    val contract = reference("contractid", contracts)
    val type = reference("typeid", invtypes)

    fun idFromPKs(contractid: Int, itemid: Int): Int {
        return contractid shl 8 or itemid
    }

    fun findFromPKs(contractid: Int, itemid: Int): ContractItem? {
        return ContractItem.findById(idFromPKs(contractid, itemid))
    }
}

class ContractItem(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ContractItem>(contractitems)

    val contractId by contractitems.contractId
    val itemId by contractitems.itemId
    val typeId by contractitems.typeId
    val quantity by contractitems.quantity
    val me by contractitems.me
    val te by contractitems.te
    val runs by contractitems.runs
    val rawBpType by contractitems.rawBpType
    val required by contractitems.required

    val contract by Contract referencedOn contractitems.contract
    val type by invtype referencedOn contractitems.type

    val bpType by lazy { BPType.valueOf(rawBpType) }

    override fun equals(other: Any?): Boolean {
        if (other == null || other !is ContractItem)
            return false

        return other.contractId == contractId && other.itemId == itemId
    }

    override fun hashCode(): Int {
        return invtypematerials.idFromPKs(contractId, itemId)
    }

    fun toBlueprint() = Blueprint.fromItem(this)

    override fun toString(): String {
        return when (bpType) {
            BPType.BPC -> type.typeName + " ($me, $te) x $runs [BPC]"
            BPType.BPO -> type.typeName + " ($me, $te) [BPO]"
            BPType.NotBP -> type.typeName + " x $quantity"
        }
    }

}

object updatelogtable : LongIdTable("updatelog", columnName = "time") {
    val time = long("time").primaryKey()
    val contracts = integer("contracts")
    val items = integer("items")
    val mutatedItems = integer("mutateditems")
    val completed = bool("completed")
    val duration = long("duration")
    val log = text("log")
    val region = integer("region")
}

class UpdateLog(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<UpdateLog>(updatelogtable) {
        fun makeDefault() = transaction {
            UpdateLog.new(Calendar.getInstance().timeInMillis) {
                contracts = -1
                items = -1
                mutatedItems = -1
                completed = false
                _duration = -1
                log = "Started"
            }
        }
    }

    val _time by updatelogtable.time
    var contracts by updatelogtable.contracts
    var items by updatelogtable.items
    var mutatedItems by updatelogtable.mutatedItems
    var completed by updatelogtable.completed
    var _duration by updatelogtable.duration
    var log by updatelogtable.log
    var region by updatelogtable.region

    val time: Interval<Millisecond>
        get() = _time.milliseconds

    var duration: Interval<*>
        get() = _duration.milliseconds
        set(i) {
            _duration = i.inMilliseconds.longValue
        }

}


object mutateditems : LongIdTable(columnName = "itemid") {
    val itemId = long("itemid").primaryKey()
    val contractId = integer("contractid")
    val typeId = integer("typeid")
    val baseTypeId = integer("basetypeid")
    val mutatorTypeId = integer("mutatortypeid")

    val type = reference("typeid", invtypes)
    val baseType = reference("basetypeid", invtypes)
    val mutatorType = reference("mutatortypeid", invtypes)
    val contract = reference("contractid", contracts)

}

class MutatedItem(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<MutatedItem>(mutateditems)

    val itemId by mutateditems.itemId

    internal var _contractId by mutateditems.contractId
    internal var _typeId by mutateditems.typeId
    internal var _baseTypeId by mutateditems.baseTypeId
    internal var _mutatorTypeId by mutateditems.mutatorTypeId

    val contractId get() = _contractId
    val typeId get() = _typeId
    val baseTypeId get() = _baseTypeId
    val mutatorTypeId get() = _mutatorTypeId

    val items by MutatedAttribute referrersOn mutatedattributes.item

    val type by invtype referencedOn mutateditems.type
    val baseType by invtype referencedOn mutateditems.baseType
    val mutatorType by invtype referencedOn mutateditems.mutatorType

    val contract by Contract referencedOn mutateditems.contract

    val baseAttributes by lazy { baseType.invtype_dgmtypeattributes_type }

    override fun hashCode(): Int {
        return itemId.toInt()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is MutatedItem)
            return false

        return other.itemId == itemId
    }
}

object mutatedattributes : LongIdTable(columnName = "itemid\" << 8 | \"attributeid") {
    val itemId = long("itemid")
    val typeId = integer("typeid")
    val attributeId = integer("attributeid")
    val baseValue = float("basevalue")
    val newValue = float("newvalue")
    val percentChange = float("percentchange")

    val item = reference("itemid", mutateditems)
    val type = reference("typeid", invtypes)
    val attribute = reference("attributeid", dgmattributetypes)
}

class MutatedAttribute(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<MutatedAttribute>(mutatedattributes) {

        fun idFromPKs(itemId: Int, attributeId: Int): Int {
            return itemId shl 8 or attributeId
        }

        fun findFromPKs(itemId: Int, attributeId: Int): dgmtypeattribute? {
            return dgmtypeattribute.findById(idFromPKs(itemId, attributeId))
        }
    }

    val itemId by mutatedattributes.itemId
    internal var _typeId by mutatedattributes.typeId
    internal var _attributeId by mutatedattributes.attributeId
    internal var _baseValue by mutatedattributes.baseValue
    internal var _newValue by mutatedattributes.newValue
    internal var _percentChange by mutatedattributes.percentChange

    val typeId get() = _typeId
    val attributeId get() = _attributeId
    val baseValue get() = _baseValue
    val newValue get() = _newValue
    val percentChange get() = _percentChange

    val item by MutatedItem referencedOn mutatedattributes.item
    val type by invtype referencedOn mutatedattributes.type
    val attribute by dgmattributetype referencedOn mutatedattributes.attribute

    val highIsGood by lazy { transaction { attribute.highIsGood } }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MutatedAttribute) return false

        if (itemId != other.itemId) return false
        if (attributeId != other.attributeId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = itemId.toInt()
        result = 31 * result + attributeId
        return result
    }


}