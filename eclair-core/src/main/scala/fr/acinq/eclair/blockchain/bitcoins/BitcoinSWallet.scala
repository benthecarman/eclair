package fr.acinq.eclair.blockchain.bitcoins

import java.nio.file.{Files, Path, Paths}

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{Satoshi, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.rpc._
import fr.acinq.eclair.blockchain.{EclairWallet, MakeFundingTxResponse}
import org.bitcoins.chain.config.ChainAppConfig
import org.bitcoins.chain.models.{BlockHeaderDb, BlockHeaderDbHelper}
import org.bitcoins.chain.pow.Pow
import org.bitcoins.core.api.FeeRateApi
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.hd.AddressType
import org.bitcoins.core.protocol.blockchain.BlockHeader
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.core.util.FutureUtil
import org.bitcoins.core.wallet.fee._
import org.bitcoins.core.wallet.utxo.TxoState
import org.bitcoins.keymanager.bip39.{BIP39KeyManager, BIP39LockedKeyManager}
import org.bitcoins.node._
import org.bitcoins.node.config.NodeAppConfig
import org.bitcoins.wallet.Wallet
import org.bitcoins.wallet.api.WalletApi
import org.bitcoins.wallet.config.WalletAppConfig
import org.bitcoins.wallet.models.{AccountDAO, SpendingInfoDb}
import scodec.bits.ByteVector

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Properties

class BitcoinSWallet(rpcClient: BitcoinJsonRPCClient, bip39PasswordOpt: Option[String] = None)(implicit system: ActorSystem,
                                                                                               walletConf: WalletAppConfig) extends EclairWallet {

  import system.dispatcher

  require(walletConf.defaultAddressType != AddressType.Legacy, "Must use segwit for LN")

  val keyManager: BIP39KeyManager = {
    val kmParams = walletConf.kmParams
    val kmE = BIP39KeyManager.initialize(kmParams, None)
    kmE match {
      case Right(km) =>
        km
      case Left(err) =>
        sys.error(s"Could not read mnemonic=$err")
    }
  }

  private val feeRateApi: FeeRateApi = {
    new FeeRateApi {
      override def getFeeRate: Future[FeeUnit] = Future.successful(SatoshisPerVirtualByte(Satoshis.one))
    }
  }

  val extendedBitcoind: BitcoinSBitcoinClient = new BitcoinSBitcoinClient(rpcClient)

  private val wallet: Wallet = Wallet(keyManager = keyManager,
    nodeApi = extendedBitcoind,
    chainQueryApi = extendedBitcoind,
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
    val output = Vector(TransactionOutput(sats, spk))
    val feeRate = SatoshisPerKW(Satoshis(feeRatePerKw))

    val fundedTxF = wallet.sendToOutputs(output, Some(feeRate), reserveUtxos = true)

    for {
      tx <- fundedTxF
      eclairTx = fr.acinq.bitcoin.Transaction.read(tx.bytes.toArray)
      outputIndex = tx.outputs.zipWithIndex
        .find(_._1.scriptPubKey == spk).get._2
      fee = feeRate.calc(tx)
    } yield MakeFundingTxResponse(eclairTx, outputIndex, Satoshi(fee.satoshis.toLong))
  }

  override def commit(tx: Transaction): Future[Boolean] = {
    publishTransaction(tx).map(_ => true)
  }

  override def rollback(tx: Transaction): Future[Boolean] = {
    val bsTx = toBitcoinsTx(tx)
    val txOutPoints = bsTx.inputs.map(_.previousOutput)
    val utxosInTxF = wallet.listUtxos(txOutPoints.toVector)

    utxosInTxF.flatMap(utxos =>
      // fixme temp hack to pass invariant
      wallet.unmarkUTXOsAsReserved(utxos.map(_.copyWithState(TxoState.Reserved))))
      .map(_ => true)
  }


  override def doubleSpent(tx: Transaction): Future[Boolean] = {
    // stolen from BitcoinCoreWallet.scala
    for {
      exists <- extendedBitcoind.getTransaction(tx.txid)
        .map(_ => true) // we have found the transaction
        .recover {
          case JsonRPCError(Error(_, message)) if message.contains("index") =>
            sys.error("Fatal error: bitcoind is indexing!!")
            System.exit(1) // bitcoind is indexing, that's a fatal error!!
            false // won't be reached
          case _ => false
        }
      doubleSpent <- if (exists) {
        // if the tx is in the blockchain, it can't have been double-spent
        Future.successful(false)
      } else {
        // if the tx wasn't in the blockchain and one of it's input has been spent, it is double-spent
        // NB: we don't look in the mempool, so it means that we will only consider that the tx has been double-spent if
        // the overriding transaction has been confirmed at least once
        Future.sequence(tx.txIn.map(txIn =>
          extendedBitcoind.isTransactionOutputSpendable(txIn.outPoint.txid, txIn.outPoint.index.toInt, includeMempool = false)))
          .map(_.exists(_ == false))
      }
    } yield doubleSpent
  }

  def listReservedUtxos: Future[Vector[SpendingInfoDb]] = {
    wallet.listUtxos(TxoState.Reserved)
  }

  def listUtxos: Future[Vector[SpendingInfoDb]] = {
    wallet.listUtxos()
  }

  def publishTransaction(tx: Transaction): Future[String] = {
    for {
      _ <- wallet.processTransaction(toBitcoinsTx(tx), None)
      resp <- extendedBitcoind.publishTransaction(tx)
    } yield resp
  }

  def hasWallet: Future[Boolean] = {
    val walletDB = walletConf.dbPath resolve walletConf.dbName
    val hdCoin = walletConf.defaultAccount.coin
    if (Files.exists(walletDB) && walletConf.seedExists()) {
      AccountDAO().read((hdCoin, 0)).map(_.isDefined)
    } else {
      Future.successful(false)
    }
  }

  private def createCallbacks(wallet: WalletApi)(implicit
                                                 ec: ExecutionContext): Future[NodeCallbacks] = {
    lazy val onCompactFilters: OnCompactFiltersReceived = { blockFilters =>
      wallet
        .processCompactFilters(blockFilters = blockFilters)
        .map(_ => ())
    }
    lazy val onBlock: OnBlockReceived = { block =>
      for {
        _ <- wallet.processBlock(block).map(_ => ())
        _ <- wallet.updateUtxoPendingStates(block.blockHeader)
      } yield ()
    }
    lazy val onHeaders: OnBlockHeadersReceived = { headers =>
      if (headers.isEmpty) {
        FutureUtil.unit
      } else {
        wallet.updateUtxoPendingStates(headers.last).map(_ => ())
      }
    }
    Future.successful(
      NodeCallbacks(onBlockReceived = Vector(onBlock),
        onCompactFiltersReceived = Vector(onCompactFilters),
        onBlockHeadersReceived = Vector(onHeaders)))
  }

  /** Creates a wallet based on the given [[WalletAppConfig]] */
  def initWallet(implicit walletConf: WalletAppConfig, ec: ExecutionContext): Future[Wallet] = {
    hasWallet.flatMap { walletExists =>
      if (walletExists) {
        // TODO change me when we implement proper password handling
        BIP39LockedKeyManager.unlock(BIP39KeyManager.badPassphrase,
          bip39PasswordOpt,
          walletConf.kmParams) match {
          case Right(_) =>
            Future.successful(wallet)
          case Left(err) =>
            sys.error(s"Error initializing key manager, err=$err")
        }
      } else {
        Wallet.initialize(wallet = wallet,
          bip39PasswordOpt = bip39PasswordOpt)
      }
    }
  }

  val genesisHeader: BlockHeader = walletConf.network.chainParams.genesisBlock.blockHeader
  val genesisHeaderDb: BlockHeaderDb = BlockHeaderDbHelper.fromBlockHeader(height = 0, chainWork = Pow.getBlockProof(genesisHeader), bh = genesisHeader)

  /** Initializes the wallet and starts the node */
  def start(): Future[BitcoinSWallet] = {
    for {
      _ <- walletConf.initialize()
      _ <- initWallet
      callbacks <- createCallbacks(wallet)
      _ = extendedBitcoind.addCallbacks(callbacks)
    } yield {
      this
    }
  }

  private def toBitcoinsTx(tx: fr.acinq.bitcoin.Transaction): org.bitcoins.core.protocol.transaction.Transaction = {
    org.bitcoins.core.protocol.transaction.Transaction.fromBytes(tx.bin)
  }
}

object BitcoinSWallet {
  val defaultDatadir: Path = Paths.get(Properties.userHome, ".bitcoin-s")

  def fromDatadir(rpcClient: BitcoinJsonRPCClient, datadir: Path = defaultDatadir)(implicit system: ActorSystem): Future[BitcoinSWallet] = {
    import system.dispatcher
    val useLogback = true
    val segwitConf = ConfigFactory.parseString("bitcoin-s.wallet.defaultAccountType = segwit")

    implicit val walletConf: WalletAppConfig = {
      val config = WalletAppConfig(datadir, useLogback, segwitConf)
      config
    }

    implicit val nodeConf: NodeAppConfig = {
      val config = NodeAppConfig(datadir, useLogback)
      config
    }

    implicit val chainConf: ChainAppConfig = {
      val config = ChainAppConfig(datadir, useLogback)
      config
    }

    for {
      _ <- chainConf.initialize()
      _ <- walletConf.initialize()
      _ <- nodeConf.initialize()
      wallet = new BitcoinSWallet(rpcClient)
      _ <- wallet.start()
    } yield wallet
  }
}
