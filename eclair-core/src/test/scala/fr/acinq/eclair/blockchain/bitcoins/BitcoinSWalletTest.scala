package fr.acinq.eclair.blockchain.bitcoins

import java.nio.file.Path

import fr.acinq.bitcoin.{Satoshi, Transaction}
import org.bitcoins.core.protocol.script.P2PKHScriptPubKey
import org.bitcoins.core.protocol.transaction.EmptyTransaction
import org.bitcoins.crypto.ECPublicKey
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.testkit.BitcoinSTestAppConfig
import org.bitcoins.testkit.node.NodeUnitTest
import org.bitcoins.testkit.rpc.BitcoindRpcTestUtil
import org.bitcoins.testkit.util.BitcoinSAsyncTest

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

class BitcoinSWalletTest extends BitcoinSAsyncTest {

  implicit val ec: ExecutionContext = system.dispatcher

  lazy val startedBitcoindRpcF: Future[BitcoindRpcClient] = BitcoindRpcTestUtil.startedBitcoindRpcClient()

  //gives us a datadir with prefix `bitcoin-s-`
  val datadir: Path = BitcoinSTestAppConfig.tmpDir()

  //get a bitcoin-s wallet connected to the bitcoind rpc client above
  //also start the neutrino node
  val bitcoinsWalletF: Future[BitcoinSWallet] = startedBitcoindRpcF.flatMap { bitcoind =>
    val peer = NodeUnitTest.peerSocketAddress(bitcoind)
    BitcoinSWallet
      .fromDatadir(datadir, peer = Some(peer))
      .flatMap(_.start())
  }

  override def beforeAll(): Unit = {
    ()
  }

  behavior of "BitcoinSWallet"

  println(s"DISABLE_SECP256K1=" + System.getenv("DISABLE_SECP256K1"))
  //this uses eclair's Secp256k1.isEnabled() on the their classpath
  //so it ignores 'DISABLE_SECP256k1'
  println(s"CryptoContext.default=${org.bitcoins.crypto.CryptoContext.default}")

  it must "generate an address" in {
    bitcoinsWalletF.flatMap(_.getFinalAddress)
      .map(addr => println(s"Address=$addr"))
      .map(_ => succeed)
  }

  it must "get balance" in {
    bitcoinsWalletF.flatMap(_.getBalance)
      .map(bal => println(s"balance=$bal"))
      .map(_ => succeed)
  }

  // should work if wallet is funded
  it must "makeFundingTx" ignore {
    val spk = P2PKHScriptPubKey(ECPublicKey.freshPublicKey)
    val sat = Satoshi(10000)
    bitcoinsWalletF.flatMap(_.makeFundingTx(spk.bytes, sat, 3))
      .map(tx => println(s"fundingtx=$tx"))
      .map(_ => succeed)
  }

  it must "commit" in {
    val tx = EmptyTransaction
    bitcoinsWalletF.flatMap(_.commit(Transaction.read(tx.bytes.toArray)))
      .map(tx => println(s"commit=$tx"))
      .map(_ => succeed)
  }

  override def afterAll: Unit = {
    val stoppedF = startedBitcoindRpcF.flatMap(b =>
      BitcoindRpcTestUtil.stopServer(b))

    Await.result(stoppedF, 5.seconds)
    super.afterAll
  }
}
