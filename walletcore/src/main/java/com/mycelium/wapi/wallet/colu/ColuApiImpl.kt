package com.mycelium.wapi.wallet.colu

import com.mrd.bitlib.model.Address
import com.mrd.bitlib.model.OutPoint
import com.mrd.bitlib.model.Transaction
import com.mrd.bitlib.util.Sha256Hash
import com.mycelium.wapi.model.TransactionOutputEx
import com.mycelium.wapi.wallet.GenericAddress
import com.mycelium.wapi.wallet.GenericTransaction
import com.mycelium.wapi.wallet.btc.BtcAddress
import com.mycelium.wapi.wallet.coins.Value
import com.mycelium.wapi.wallet.colu.coins.ColuMain
import com.mycelium.wapi.wallet.colu.json.ColuBroadcastTxHex
import java.io.IOException


class ColuApiImpl(val coluClient: ColuClient) : ColuApi {
    override fun prepareTransaction(toAddress: BtcAddress, fromBtcAddress: List<BtcAddress>, amount: Value, txFee: Value): ColuBroadcastTxHex.Json? {
        val fromAddress = mutableListOf<Address>()
        fromBtcAddress.forEach {
            fromAddress.add(it.address)
        }
        return coluClient.prepareTransaction(toAddress.address, fromAddress, amount, txFee.getValue())

    }

    @Throws(IOException::class)
    override fun broadcastTx(coluSignedTransaction: Transaction): String? {
        val result = coluClient.broadcastTransaction(coluSignedTransaction)
        return result.txid
    }

    override fun getAddressTransactions(address: GenericAddress): ColuApi.ColuTransactionsInfo? {
        var result: ColuApi.ColuTransactionsInfo? = null
        try {
            val json = coluClient.getAddressTransactions(address.toString())
            val transactions = mutableListOf<ColuTransaction>()
            for (transaction in json.transactions) {
                var transferred = Value.zeroValue(address.coinType)

                val input = mutableListOf<GenericTransaction.GenericInput>()
                transaction.vin.forEach { vin ->
                    vin.assets.filter { it.assetId == address.coinType.id }.forEach { asset ->
                        val value = Value.valueOf(address.coinType, asset.amount)
                        val _address = Address.fromString(vin.previousOutput.addresses[0])
                        input.add(GenericTransaction.GenericInput(
                                BtcAddress(address.coinType, _address), value, false))
                        if (vin.previousOutput.addresses.contains(address.toString())) {
                            transferred = transferred.subtract(value)
                        }
                    }
                }

                val output = mutableListOf<GenericTransaction.GenericOutput>()
                transaction.vout.forEach { vout ->
                    vout.assets.filter { it.assetId == address.coinType.id }.forEach { asset ->
                        val value = Value.valueOf(address.coinType, asset.amount)
                        val _address = Address.fromString(vout.scriptPubKey.addresses[0])
                        output.add(GenericTransaction.GenericOutput(
                                BtcAddress(address.coinType, _address), value, false))
                        if (vout.scriptPubKey.addresses.contains(address.toString())) {
                            transferred = transferred.add(value)
                        }
                    }
                }

                if (input.size > 0 || output.size > 0) {
                    transactions.add(ColuTransaction(Sha256Hash.fromString(transaction.txid), address.coinType
                            , transferred
                            , transaction.time / 1000, null, transaction.blockheight.toInt()
                            , transaction.confirmations, false, output[0].address, input, output))
                }
            }
            val utxos = mutableListOf<TransactionOutputEx>()
            for (utxo in json.utxos) {
                utxo.assets.filter { it.assetId == address.coinType.id }.forEach { asset ->
                    utxos.add(TransactionOutputEx(OutPoint(Sha256Hash.fromString(utxo.txid), utxo.index), utxo.blockheight,
                            asset.amount, utxo.scriptPubKey.asm.toByteArray(), false))
                }
            }
            result = ColuApi.ColuTransactionsInfo(transactions, utxos, Value.valueOf(address.coinType, json.balance))
        } catch (e: IOException) {
            //Log.e("ColuApiImpl", "", e)
        }
        return result
    }

    override fun getCoinTypes(address: Address): List<ColuMain> {
        val assetsList = mutableListOf<ColuMain>()
        try {
            val addressInfo = coluClient.getBalance(address)
            if (addressInfo != null) {
                if (addressInfo.utxos != null) {
                    for (utxo in addressInfo.utxos) {
                        // adding utxo to list of txid list request
                        for (txidAsset in utxo.assets) {
                            for (coin in ColuUtils.allColuCoins()) {
                                if (txidAsset.assetId == coin.id) {
                                    assetsList.add(coin)
                                }
                            }
                        }
                    }
                }
            }
        } catch (ignore: IOException) {
        }
        return assetsList
    }
}
