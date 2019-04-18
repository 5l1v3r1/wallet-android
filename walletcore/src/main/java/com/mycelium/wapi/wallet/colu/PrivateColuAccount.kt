package com.mycelium.wapi.wallet.colu

import com.google.common.base.Optional
import com.mrd.bitlib.FeeEstimatorBuilder
import com.mrd.bitlib.crypto.BipDerivationType
import com.mrd.bitlib.crypto.InMemoryPrivateKey
import com.mrd.bitlib.model.*
import com.mycelium.wapi.model.TransactionOutputSummary
import com.mycelium.wapi.wallet.*
import com.mycelium.wapi.wallet.btc.BtcAddress
import com.mycelium.wapi.wallet.coins.CryptoCurrency
import com.mycelium.wapi.wallet.coins.Value
import org.apache.commons.codec.binary.Hex
import org.bitcoinj.core.ECKey
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import java.util.*


class PrivateColuAccount(context: ColuAccountContext, val privateKey: InMemoryPrivateKey
                         , coluCoinType: CryptoCurrency
                         , networkParameters: NetworkParameters
                         , coluClient: ColuApi
                         , accountBacking: AccountBacking<ColuTransaction>
                         , backing: WalletBacking<ColuAccountContext, ColuTransaction>
                         , listener: AccountListener? = null)
    : PublicColuAccount(context, coluCoinType, networkParameters
        , coluClient, accountBacking, backing, listener), ExportableAccount {

    override fun isSyncing(): Boolean {
        //TODO: implement later
        return false
    }

    override fun getPrivateKey(cipher: KeyCipher?): InMemoryPrivateKey {
        return privateKey
    }

    override fun calculateMaxSpendableAmount(minerFeeToUse: Long, destinationAddress: BtcAddress): Value {
        return accountBalance.spendable
    }

    override fun broadcastOutgoingTransactions(): Boolean {
        return false
    }

    override fun completeTransaction(request: SendRequest<ColuTransaction>) {
        if (request is ColuSendRequest) {
            val fromAddresses = mutableListOf(receiveAddress as BtcAddress)
            fromAddresses.addAll(request.fundingAddress)
            val hexString = coluClient.prepareTransaction(request.destination, fromAddresses, request.amount, request.fee)
            request.txHex = hexString
            if (request.txHex == null) {
                throw Exception("transaction not complete")
            }
            val txBytes: ByteArray?
            try {
                txBytes = Hex.decodeHex(request.txHex?.toCharArray())
            } catch (e: org.apache.commons.codec.DecoderException) {
                return
            }
            if (txBytes == null) {
                return
            }

            request.baseTransaction = Transaction.fromBytes(txBytes)
            request.isCompleted = true
        } else {
            TODO("completeTransaction not implemented for ${request.javaClass.simpleName}")
        }
    }

    override fun signTransaction(request: SendRequest<ColuTransaction>, keyCipher: KeyCipher) {
        if (!request.isCompleted) {
            return
        }
        if (request is ColuSendRequest) {
            request.baseTransaction?.let {
                for ((index, input) in it.inputs.withIndex()) {
                    for (fundingAccount in request.fundingAccounts) {
                        val output = fundingAccount.getTx(input.outPoint.txid).outputs[input.outPoint.index]
                        if (fundingAccount is InputSigner && fundingAccount.isMineAddress(output.address)) {
                            fundingAccount.signInput(GenericInput(it, input, index), keyCipher)
                        }
                    }
                    if (input.script.scriptBytes.isEmpty()) {
                        throw Exception("input ${input.outPoint} not signed")
                    }
                }
                val tx = Transaction(it.version, it.inputs, it.outputs, it.lockTime)

                val txBytes = Hex.decodeHex(request.txHex?.toCharArray());
                val privKey = request.fundingAccounts[0].getPrivateKey(keyCipher)
                val signTx = org.bitcoinj.core.Transaction(TestNet3Params.get(), txBytes)
                val ecKey = ECKey.fromPrivateAndPrecalculatedPublic(privKey.privateKeyBytes, privKey.publicKey.publicKeyBytes)
                val inputScript = ScriptBuilder.createOutputScript(ecKey.toAddress(TestNet3Params.get()))

                for (i in 0..signTx.inputs.size - 1) {
                    val signature = signTx.calculateSignature(i, ecKey, inputScript, org.bitcoinj.core.Transaction.SigHash.ALL, false)
                    val scriptSig = ScriptBuilder.createInputScript(signature, ecKey)
                    signTx.getInput(i.toLong()).setScriptSig(scriptSig)
                }
                val signedTransactionBytes = signTx.bitcoinSerialize()
                val tx2 = Transaction.fromBytes(signedTransactionBytes)

                request.tx = ColuTransaction(tx.id, coinType, Value.zeroValue(coinType), 0,
                        tx, 0, 0, false, listOf(), listOf())
            }
        } else {
            TODO("signTransaction not implemented for ${request.javaClass.simpleName}")
        }
    }

    override fun setAllowZeroConfSpending(allowZeroConfSpending: Boolean) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun broadcastTx(tx: ColuTransaction): BroadcastResult {
        return if (tx.tx != null && coluClient.broadcastTx(tx.tx) != null) {
            BroadcastResult(BroadcastResultType.SUCCESS)
        } else {
            BroadcastResult(BroadcastResultType.REJECTED)
        }
    }

    override fun getSendToRequest(destination: BtcAddress, amount: Value, feePerKb: Value): SendRequest<ColuTransaction> {
        return ColuSendRequest(coinType, destination, amount, feePerKb)
    }

    override fun getSyncTotalRetrievedTransactions(): Int = 0

    override fun canSpend(): Boolean = true

    override fun getExportData(cipher: KeyCipher): ExportableAccount.Data {
        var privKey = Optional.absent<String>()
        val publicDataMap = HashMap<BipDerivationType, String>()
        if (canSpend()) {
            try {
                privKey = Optional.of(this.privateKey.getBase58EncodedPrivateKey(networkParameters))
            } catch (ignore: KeyCipher.InvalidKeyCipher) {
            }

        }
        publicDataMap[BipDerivationType.getDerivationTypeByAddressType(AddressType.P2PKH)] = receiveAddress.toString()
        return ExportableAccount.Data(privKey, publicDataMap)

    }

    fun getUnspentTransactionOutputSummary(): List<TransactionOutputSummary> {
        // Get all unspent outputs for this account
        val outputs = accountBacking.allUnspentOutputs

        // Transform it to a list of summaries
        val list = ArrayList<TransactionOutputSummary>()
        val blockChainHeight = blockChainHeight
        for (output in outputs) {

            val script = ScriptOutput.fromScriptBytes(output.script)
            val address: Address
            address = if (script == null) {
                Address.getNullAddress(networkParameters)
                // This never happens as we have parsed this script before
            } else {
                script.getAddress(networkParameters)
            }
            val confirmations: Int = if (output.height == -1) {
                0
            } else {
                Math.max(0, blockChainHeight - output.height + 1)
            }

            val summary = TransactionOutputSummary(output.outPoint, output.value, output.height, confirmations, address)
            list.add(summary)
        }
        // Sort & return
        list.sort()
        return list
    }

    override fun getTypicalEstimatedTransactionSize(): Int {
        // Colu transaction is a typical bitcoin transaction containing additional inputs and OP_RETURN data
        // Typical Colu transaction has 2 inputs and 4 outputs
        return FeeEstimatorBuilder().setLegacyInputs(2)
                .setLegacyOutputs(4)
                .createFeeEstimator()
                .estimateTransactionSize()
    }

}