package com.rnett.ligraph.eve.contracts.blueprints

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.JsonAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.rnett.eve.ligraph.sde.InvTypeAdapter
import com.rnett.eve.ligraph.sde.industryactivityrecipe
import com.rnett.eve.ligraph.sde.invtype
import com.rnett.ligraph.eve.contracts.ContractItem
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.IOException

class BlueprintAdapter : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T> {
        val delegate = gson.getDelegateAdapter(this, TypeToken.get(invtype::class.java))

        return object : TypeAdapter<T>() {
            @Throws(IOException::class)
            override fun write(out: JsonWriter, value: T) {
                value as Blueprint

                out.beginObject()

                out.name("bpType")
                InvTypeAdapter().write(out, value.bpType)

                out.name("isCopy").value(value.isCopy)

                if (value.isCopy)
                    out.name("runs").value(value.runs)

                out.name("me").value(value.me)
                out.name("te").value(value.te)

                out.endObject()
            }

            @Throws(IOException::class)
            override fun read(input: JsonReader): T {

                input.beginObject()

                var bpType: invtype? = null
                var isCopy: Boolean? = null
                var runs: Int? = null
                var me: Int? = null
                var te: Int? = null

                while (input.hasNext()) {
                    when (input.nextName()) {
                        "bpType" -> bpType = InvTypeAdapter().read(input)
                        "isCopy" -> isCopy = input.nextBoolean()
                        "runs" -> runs = input.nextInt()
                        "me" -> runs = input.nextInt()
                        "te" -> runs = input.nextInt()
                    }
                }

                input.endObject()

                return if (isCopy!!)
                    BPC(bpType!!, runs!!, me!!, te!!) as T
                else
                    BPO(bpType!!, me!!, te!!) as T
            }
        }
    }
}

@JsonAdapter(BlueprintAdapter::class)
abstract class Blueprint(val bpType: invtype, val runs: Int, val me: Int = 0, val te: Int = 0) {
    val isCopy: Boolean = runs > 0
    val type: BPType = if (isCopy) BPType.BPC else BPType.BPO

    val recipe: industryactivityrecipe
    val productType: invtype

    init {
        recipe = transaction { bpType.invtype_industryactivityrecipes_type.filter { it.activityID == 1 }.first() }
        productType = transaction { recipe.productType }

    }

    fun asBpo(): BPO? =
            if (this is BPO) this
            else null

    fun asBpc(): BPC? =
            if (this is BPC) this
            else null

    companion object {
        fun fromItem(item: ContractItem): Blueprint {

            if (item.bpType == BPType.NotBP)
                throw IllegalArgumentException("Item is not a blueprint")

            return if (item.bpType == BPType.BPC) {
                BPC(item.type, item.runs!!, item.me ?: 0, item.te ?: 0)
            } else {
                BPO(item.type, item.me ?: 0, item.te ?: 0)
            }
        }
    }

}

enum class BPType {
    BPC, BPO, NotBP;
}

@JsonAdapter(BlueprintAdapter::class)
class BPC(bpType: invtype, runs: Int, me: Int = 0, te: Int = 0) : Blueprint(bpType, runs, me, te) {

    init {
        if (runs <= 0)
            throw IllegalArgumentException("Runs must be greater than 0.")
    }

    companion object {
        fun forProduct(productType: invtype, runs: Int, me: Int = 0, te: Int = 0): BPC {
            return BPC(
                    transaction { productType.invtype_industryactivityrecipes_productType.first().type },
                    runs, me, te
            )
        }
    }

    override fun toString(): String = "${bpType.typeName} ($me, $te) x $runs [BPC]"

}

@JsonAdapter(BlueprintAdapter::class)
class BPO(bpType: invtype, me: Int = 0, te: Int = 0) : Blueprint(bpType, 0, me, te) {

    companion object {
        fun forProduct(productType: invtype, me: Int = 0, te: Int = 0): BPO {
            return BPO(
                    transaction { productType.invtype_industryactivityrecipes_productType.first().type },
                    me, te
            )
        }
    }

    override fun toString(): String = "${bpType.typeName} ($me, $te) [BPO]"
}