package com.rnett.ligraph.eve.contracts.blueprints

import com.rnett.eve.ligraph.sde.industryactivityrecipe
import com.rnett.eve.ligraph.sde.invtype
import org.jetbrains.exposed.sql.transactions.transaction

abstract class Blueprint(val bpType: invtype, val runs: Int, val me: Int = 0, val te: Int = 0) {
    val isCopy: Boolean = runs < 0

    val recipe: industryactivityrecipe
    val productType: invtype

    init {
        recipe = bpType.invtype_industryactivityrecipes_type.filter { it.activityID == 1 }.first()
        productType = recipe.productType
    }

    fun asBpo(): BPO? =
            if (this is BPO) this
            else null

    fun asBpc(): BPC? =
            if (this is BPC) this
            else null
}


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

}

class BPO(bpType: invtype, me: Int = 0, te: Int = 0) : Blueprint(bpType, -1, me, te) {

    companion object {
        fun forProduct(productType: invtype, me: Int = 0, te: Int = 0): BPO {
            return BPO(
                    transaction { productType.invtype_industryactivityrecipes_productType.first().type },
                    me, te
            )
        }
    }
}