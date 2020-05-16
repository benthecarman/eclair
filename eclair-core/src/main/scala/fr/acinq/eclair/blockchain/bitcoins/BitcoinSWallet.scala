package fr.acinq.eclair.blockchain.bitcoins

import java.io.File
import java.nio.file.Files

import fr.acinq.bitcoin.{Satoshi, Transaction}
import fr.acinq.eclair.blockchain.{EclairWallet, MakeFundingTxResponse}
import org.bitcoins.wallet.Wallet
import org.bitcoins.core.api.{ChainQueryApi, NodeApi, NodeChainQueryApi}
import org.bitcoins.core.crypto.{DoubleSha256Digest, DoubleSha256DigestBE}
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.hd.HDChainType
import org.bitcoins.core.protocol.{BitcoinAddress, BlockStamp}
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.core.wallet.fee.{SatoshisPerKiloByte, SatoshisPerVirtualByte}
import org.bitcoins.keymanager.KeyManagerParams
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.bitcoins.wallet.config.WalletAppConfig
import org.bitcoins.wallet.models.AccountDAO
import scodec.bits.ByteVector

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
class BitcoinSWallet()(implicit ec: ExecutionContext, walletConf: WalletAppConfig) extends EclairWallet {

  val keyManager: BIP39KeyManager = {
    val kmParams = walletConf.kmParams
    val kmE = BIP39KeyManager.initialize(kmParams,None)
    kmE match {
      case Right(km) =>
        km
      case Left(err) =>
        sys.error(s"Could not read mnemonic=${err}")
    }
  }

  val wallet = Wallet(keyManager, nodeApi, chainQueryApi)

  override def getBalance: Future[Satoshi] = {
    wallet.getBalance().map(sat => Satoshi(sat.satoshis.toLong))
  }

  override def getFinalAddress: Future[String] = {
    wallet.getNewAddress().map(_.value)
  }

  override def makeFundingTx(pubkeyScript: ByteVector,
                             amount: Satoshi,
                             feeRatePerKw: Long): Future[MakeFundingTxResponse] = {
    val spk = ScriptPubKey.fromAsmBytes(pubkeyScript)
    val sats = Satoshis(amount.toLong)
    val output = Vector(TransactionOutput(sats,spk))
    val feeRate = SatoshisPerKiloByte(Satoshis(feeRatePerKw))
    val addressF = Future.fromTry(BitcoinAddress.fromScriptPubKey(spk,walletConf.network))
    val fundedTxF = wallet.fundRawTransaction(output,
      feeRate,
      markAsReserved = true)

    for {
      tx <- fundedTxF
      eclairTx = fr.acinq.bitcoin.Transaction.read(tx.bytes.toArray)
      outputIndex = tx.outputs.zipWithIndex
        .find(_._1.scriptPubKey == spk).get._2
      fee = feeRate.calc(tx)
    } yield MakeFundingTxResponse(eclairTx,outputIndex,Satoshi(fee.satoshis.toLong))
  }

  override def commit(tx: Transaction): Future[Boolean] = ???

  override def rollback(tx: Transaction): Future[Boolean] = ???


  override def doubleSpent(tx: Transaction): Future[Boolean] = ???


  def hasWallet(): Future[Boolean] = {
    val walletDB = walletConf.dbPath resolve walletConf.dbName
    val hdCoin = walletConf.defaultAccount.coin
    if (Files.exists(walletDB) && walletConf.seedExists()) {
      AccountDAO().read((hdCoin, 0)).map(_.isDefined)
    } else {
      Future.successful(false)
    }
  }

  def initialize(): Future[BitcoinSWallet] = {
    Wallet.initialize(wallet, None)
      .map(_ => this)
  }

  private val nodeApi: NodeApi = new NodeApi {
    override def downloadBlocks(blockHashes: Vector[DoubleSha256Digest]): Future[Unit] = {
      ???
    }
  }
  private val chainQueryApi: ChainQueryApi = new ChainQueryApi {
    override def getBlockHeight(blockHash: DoubleSha256DigestBE): Future[Option[Int]] = ???

    override def getBestBlockHash(): Future[DoubleSha256DigestBE] = ???

    override def getNumberOfConfirmations(blockHashOpt: DoubleSha256DigestBE): Future[Option[Int]] = ???

    override def getFilterCount: Future[Int] = ???

    override def getHeightByBlockStamp(blockStamp: BlockStamp): Future[Int] = ???

    override def getFiltersBetweenHeights(startHeight: Int, endHeight: Int): Future[Vector[ChainQueryApi.FilterResponse]] = ???
  }
}

object BitcoinSWallet {

  def fromDefaultDatadir()(implicit ec: ExecutionContext): Future[BitcoinSWallet] = {
    implicit val walletConf: WalletAppConfig = {
      val config = WalletAppConfig.fromDefaultDatadir()
      Await.result(config.initialize(),10.seconds)
      config
    }
    val wallet = new BitcoinSWallet()
    wallet.hasWallet().flatMap {
      case true => Future.successful(wallet)
      case false =>
        wallet.initialize()
    }
  }
}
