package fr.acinq.eclair.blockchain.bitcoins

import java.net.InetSocketAddress
import java.nio.file.{Files, Path, Paths}

import akka.actor.ActorSystem
import fr.acinq.bitcoin.{Satoshi, Transaction}
import fr.acinq.eclair.blockchain.{EclairWallet, MakeFundingTxResponse}
import org.bitcoins.chain.config.ChainAppConfig
import org.bitcoins.core.api.{FeeRateApi, NodeApi}
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.core.wallet.fee.{FeeUnit, SatoshisPerKiloByte, SatoshisPerVirtualByte}
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.bitcoins.node.NeutrinoNode
import org.bitcoins.node.config.NodeAppConfig
import org.bitcoins.node.models.Peer
import org.bitcoins.wallet.Wallet
import org.bitcoins.wallet.config.WalletAppConfig
import org.bitcoins.wallet.models.AccountDAO
import scodec.bits.ByteVector

import scala.concurrent.Future
import scala.util.Properties

class BitcoinSWallet(val peerOpt: Option[InetSocketAddress])(implicit system: ActorSystem,
                       walletConf: WalletAppConfig,
                       nodeConf: NodeAppConfig,
                       chainConf: ChainAppConfig) extends EclairWallet {

  import system.dispatcher

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

  private val feeRateApi: FeeRateApi = {
    new FeeRateApi {
      override def getFeeRate: Future[FeeUnit] = Future.successful(SatoshisPerVirtualByte(Satoshis.one))
    }
  }
  private val nodeApi: NodeApi = {
    neutrinoNode
  }

  private val neutrinoNode: NeutrinoNode = {
    val peerSocket = peerOpt match {
      case Some(socket) => socket
      case None =>
        parseInetSocketAddress(nodeConf.peers.head, nodeConf.network.port)
    }

    val peer = Peer.fromSocket(peerSocket)
    NeutrinoNode(peer, nodeConf,chainConf,system)
  }

  private val wallet = Wallet(keyManager = keyManager,
    nodeApi = nodeApi,
    chainQueryApi = neutrinoNode,
    creationTime = keyManager.creationTime,
    feeRateApi = feeRateApi
  )

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

  override def commit(tx: Transaction): Future[Boolean] = {
    val bstx = toBitcoinsTx(tx)
    nodeApi.broadcastTransaction(bstx)
      .map(_ => true)
  }

  override def rollback(tx: Transaction): Future[Boolean] = {
    val bsTx = toBitcoinsTx(tx)
    val allUtxosF = wallet.listUtxos()
    val utxosInTxF = for {
      utxos <- allUtxosF
    } yield {
      val txOutPoints = bsTx.inputs.map(_.previousOutput)
      utxos.filter(si => txOutPoints.contains(si.outPoint))
    }

    utxosInTxF.flatMap(wallet.unmarkUTXOsAsReserved)
      .map(_ => true)
  }


  override def doubleSpent(tx: Transaction): Future[Boolean] = {
    //comeback later and implement, this seems to be optional though
    Future.successful(false)
  }


  def hasWallet(): Future[Boolean] = {
    val walletDB = walletConf.dbPath resolve walletConf.dbName
    val hdCoin = walletConf.defaultAccount.coin
    if (Files.exists(walletDB) && walletConf.seedExists()) {
      AccountDAO().read((hdCoin, 0)).map(_.isDefined)
    } else {
      Future.successful(false)
    }
  }

  /** Initializes the wallet and starts the node */
  def start(): Future[BitcoinSWallet] = {
    Wallet.initialize(wallet, None)
      .flatMap(_ => neutrinoNode.start())
      .map(_ => this)
  }

  def stop(): Future[Unit] = {
    neutrinoNode.stop()
      .map(_ => ())
  }

  private def parseInetSocketAddress(
                                      address: String,
                                      defaultPort: Int): InetSocketAddress = {

    def parsePort(port: String): Int = {
      lazy val errorMsg = s"Invalid peer port: $address"
      try {
        val res = port.toInt
        if (res < 0 || res > 0xffff) {
          throw new RuntimeException(errorMsg)
        }
        res
      } catch {
        case _: NumberFormatException =>
          throw new RuntimeException(errorMsg)
      }
    }

    address.split(":") match {
      case Array(host)       => new InetSocketAddress(host, defaultPort)
      case Array(host, port) => new InetSocketAddress(host, parsePort(port))
      case _                 => throw new RuntimeException(s"Invalid peer address: $address")
    }
  }

  private def toBitcoinsTx(tx: fr.acinq.bitcoin.Transaction): org.bitcoins.core.protocol.transaction.Transaction = {
    org.bitcoins.core.protocol.transaction.Transaction.fromBytes(tx.bin)
  }
}

object BitcoinSWallet {
  val defaultDatadir = Paths.get(Properties.userHome, ".bitcoin-s")

  def fromDefaultDatadir()(implicit system: ActorSystem): Future[BitcoinSWallet] = {
    fromDatadir(defaultDatadir)
  }

  def fromDatadir(datadir: Path, peer: Option[InetSocketAddress] = None)(implicit system: ActorSystem): Future[BitcoinSWallet] = {
      import system.dispatcher
    val useLogback = true
      implicit val walletConf: WalletAppConfig = {
        val config = WalletAppConfig(datadir,useLogback)
        config
      }

      implicit val nodeConf: NodeAppConfig = {
        val config = NodeAppConfig(datadir,useLogback)
        config
      }

      implicit val chainConf: ChainAppConfig = {
        val config = ChainAppConfig(datadir,useLogback)
        config
      }

      for {
        _ <- chainConf.initialize()
        _ <- walletConf.initialize()
        _ <- nodeConf.initialize()
        wallet = new BitcoinSWallet(peer)
        _ <- wallet.start()
      } yield wallet
    }
}
