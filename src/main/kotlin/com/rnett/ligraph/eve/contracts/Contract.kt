package com.rnett.ligraph.eve.contracts

import com.google.gson.annotations.SerializedName
import org.joda.time.DateTime

interface IContract {
    val collateral: Int
    val contractId: Int
    val daysToComplete: Int
    val endLocationId: Int
    val forCorporation: Boolean
    val issuerCorporationId: Int
    val issuerId: Int
    val price: Double
    val reward: Double
    val startLocationId: Int
    val title: String
    val volume: Double

    val dateExpired: DateTime
    val dateIssued: DateTime

    val expired get() = dateExpired > DateTime.now()

    val type: ContractType
}

enum class ContractType(val raw: String, val displayName: String, val hasItems: Boolean = false) {
    ItemExchange("item_exchange", "Item Exchange", true), Courier("courier", "Courier"),
    Auction("auction", "Auction", true), Trade("trade", "Item Exchange (w/ Required Items)", true),
    Other("other", "Unknown");

    companion object {
        fun fromRaw(raw: String) = ContractType.values().find { it.raw == raw } ?: Other
    }
}

data class GsonContract(@SerializedName("collateral") override val collateral: Int,
                        @SerializedName("contract_id") override val contractId: Int,
                        @SerializedName("date_expired") val rawDateExpired: String,
                        @SerializedName("date_issued") val rawDateIssued: String,
                        @SerializedName("days_to_complete") override val daysToComplete: Int,
                        @SerializedName("end_location_id") override val endLocationId: Int,
                        @SerializedName("for_corporation") override val forCorporation: Boolean,
                        @SerializedName("issuer_corporation_id") override val issuerCorporationId: Int,
                        @SerializedName("issuer_id") override val issuerId: Int,
                        @SerializedName("price") override val price: Double,
                        @SerializedName("reward") override val reward: Double,
                        @SerializedName("start_location_id") override val startLocationId: Int,
                        @SerializedName("title") override val title: String,
                        @SerializedName("type") val rawType: String,
                        @SerializedName("volume") override val volume: Double) : IContract {

    override val type: ContractType get() = ContractType.fromRaw(rawType)

    override val dateExpired get() = DateTime(rawDateExpired)
    override val dateIssued get() = DateTime(rawDateIssued)
}
