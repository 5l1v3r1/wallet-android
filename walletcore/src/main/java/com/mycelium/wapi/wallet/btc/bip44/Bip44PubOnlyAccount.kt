package com.mycelium.wapi.wallet.btc.bip44

import com.mrd.bitlib.crypto.BipDerivationType
import com.mrd.bitlib.model.NetworkParameters
import com.mycelium.wapi.api.Wapi
import com.mycelium.wapi.wallet.btc.Bip44AccountBacking


open class Bip44PubOnlyAccount(
        context: HDAccountContext,
        keyManagerMap: Map<BipDerivationType, HDAccountKeyManager>,
        network: NetworkParameters,
        backing: Bip44AccountBacking,
        wapi: Wapi
) : Bip44Account(context, keyManagerMap, network, backing, wapi) {

    override fun canSpend(): Boolean {
        return false
    }
}