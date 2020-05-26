package fr.acinq.eclair.blockchain.bitcoins

import fr.acinq.eclair.TestKitBaseClass
import org.bitcoin.{NativeSecp256k1, Secp256k1Context}
import org.bitcoins.testkit.BitcoinSTestAppConfig
import org.bitcoins.testkit.node.NodeUnitTest
import org.bitcoins.testkit.rpc.BitcoindRpcTestUtil
import org.bitcoins.testkit.util.BitcoinSAsyncTest
import org.scalatest.funsuite.AnyFunSuiteLike
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.DurationInt

class BitcoinSWalletTest extends BitcoinSAsyncTest {

  implicit val ec: ExecutionContext = system.dispatcher

  lazy val startedBitcoindRpcF = BitcoindRpcTestUtil.startedBitcoindRpcClient()

  //gives us a datadir with prefix `bitcoin-s-`
  val datadir = BitcoinSTestAppConfig.tmpDir()

  //get a bitcoin-s wallet connected to the bitcoind rpc client above
  //also start the neutrino node
  val bitcoinsWalletF = startedBitcoindRpcF.flatMap { bitcoind =>
    val peer = NodeUnitTest.peerSocketAddress(bitcoind)
    BitcoinSWallet
      .fromDatadir(datadir, peer = Some(peer))
      .flatMap(_.start())
  }

  override def beforeAll(): Unit = {
    ()
  }

  behavior of "BitcoinSWallet"

  it must "generate an address" in {
    println(s"DISABLE_SECP256K1="+ System.getenv("DISABLE_SECP256K1"))
    //this uses eclair's Secp256k1.isEnabled() on the their classpath
    //so it ignores 'DISABLE_SECP256k1'
    println(s"Secp256k1.isEnabled=${Secp256k1Context.isEnabled}")
    bitcoinsWalletF.flatMap(_.getFinalAddress)
      .map(addr => println(s"Address=${addr}"))
      .map(_ => succeed)
  }

  override def afterAll: Unit = {
    val stoppedF = startedBitcoindRpcF.flatMap(b =>
      BitcoindRpcTestUtil.stopServer(b))

    Await.result(stoppedF,5.seconds)
    super.afterAll
  }
}
