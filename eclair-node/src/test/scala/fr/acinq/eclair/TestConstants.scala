package fr.acinq.eclair

import fr.acinq.bitcoin.{Base58Check, Crypto, OP_0, OP_PUSHDATA, Script}
import fr.acinq.bitcoin.Crypto.{PrivateKey, Scalar}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.transactions.Scripts

/**
  * Created by PM on 26/04/2016.
  */
object TestConstants {
  val fundingSatoshis = 1000000L
  val pushMsat = 200000000L

  object Alice {
    val id = randomKey.publicKey
    val channelParams = LocalParams(
      dustLimitSatoshis = 542,
      maxHtlcValueInFlightMsat = 150000000,
      channelReserveSatoshis = 10000, // Bob will need to keep that much satoshis as direct payment
      htlcMinimumMsat = 0,
      feeratePerKw = 10000,
      toSelfDelay = 144,
      maxAcceptedHtlcs = 100,
      fundingPrivKey = PrivateKey(Array.fill[Byte](32)(1), compressed = true),
      revocationSecret = PrivateKey(Array.fill[Byte](32)(2), compressed = true),
      paymentKey = PrivateKey(Array.fill[Byte](32)(3), compressed = true),
      delayedPaymentKey = PrivateKey(Array.fill[Byte](32)(4), compressed = true),
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(Array.fill[Byte](32)(5), compressed = true).publicKey)),
      shaSeed = Crypto.sha256("alice-seed".getBytes()),
      isFunder = true,
      globalFeatures = "",
      localFeatures = "00" // no announcement
    )
  }

  object Bob {
    val id = randomKey.publicKey
    val channelParams = LocalParams(
      dustLimitSatoshis = 542,
      maxHtlcValueInFlightMsat = Long.MaxValue, // Bob has no limit on the combined max value of in-flight htlcs
      channelReserveSatoshis = 20000, // Alice will need to keep that much satoshis as direct payment
      htlcMinimumMsat = 1000,
      feeratePerKw = 10000,
      toSelfDelay = 144,
      maxAcceptedHtlcs = 30,
      fundingPrivKey = PrivateKey(Array.fill[Byte](32)(11), compressed = true),
      revocationSecret = PrivateKey(Array.fill[Byte](32)(12), compressed = true),
      paymentKey = PrivateKey(Array.fill[Byte](32)(13), compressed = true),
      delayedPaymentKey = PrivateKey(Array.fill[Byte](32)(14), compressed = true),
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(Array.fill[Byte](32)(15), compressed = true).publicKey)),
      shaSeed = Crypto.sha256("alice-seed".getBytes()),
      isFunder = false,
      globalFeatures = "",
      localFeatures = "00" // no announcement
    )
  }

}
