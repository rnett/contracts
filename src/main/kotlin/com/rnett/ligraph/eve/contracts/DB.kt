package com.rnett.ligraph.eve.contracts

import com.rnett.eve.ligraph.sde.invtype
import com.rnett.eve.ligraph.sde.invtypematerials
import com.rnett.eve.ligraph.sde.invtypes
import com.rnett.ligraph.eve.contracts.blueprints.BPC
import com.rnett.ligraph.eve.contracts.blueprints.BPO
import com.rnett.ligraph.eve.contracts.blueprints.BPType
import com.rnett.ligraph.eve.contracts.blueprints.Blueprint
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.IntIdTable
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

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
    val rawType by contracts.rawType
    val rawVolume by contracts.rawVolume

    override val type: ContractType by lazy { ContractType.fromRaw(rawType) }

    override val dateExpired by lazy { DateTime(rawDateExpired) }
    override val dateIssued by lazy { DateTime(rawDateIssued) }

    override val price by lazy { rawPrice.toDouble() }
    override val reward by lazy { rawReward.toDouble() }
    override val volume by lazy { rawVolume.toDouble() }

    val items by ContractItem referrersOn contractitems.contract

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