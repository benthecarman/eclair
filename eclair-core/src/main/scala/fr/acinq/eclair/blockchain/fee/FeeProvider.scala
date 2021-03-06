/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.fee

import fr.acinq.eclair._

import scala.concurrent.Future

/**
 * Created by PM on 09/07/2017.
 */
trait FeeProvider {
  def getFeerates: Future[FeeratesPerKB]
}

case object CannotRetrieveFeerates extends RuntimeException("cannot retrieve feerates: channels may be at risk")

// stores fee rate in satoshi/kb (1 kb = 1000 bytes)
case class FeeratesPerKB(block_1: Long, blocks_2: Long, blocks_6: Long, blocks_12: Long, blocks_36: Long, blocks_72: Long, blocks_144: Long) {
  require(block_1 > 0 && blocks_2 > 0 && blocks_6 > 0 && blocks_12 > 0 && blocks_36 > 0 && blocks_72 > 0 && blocks_144 > 0, "all feerates must be strictly greater than 0")

  def feePerBlock(target: Int): Long = target match {
    case 1 => block_1
    case 2 => blocks_2
    case t if t <= 6 => blocks_6
    case t if t <= 12 => blocks_12
    case t if t <= 36 => blocks_36
    case t if t <= 72 => blocks_72
    case _ => blocks_144
  }
}

// stores fee rate in satoshi/kw (1 kw = 1000 weight units)
case class FeeratesPerKw(block_1: Long, blocks_2: Long, blocks_6: Long, blocks_12: Long, blocks_36: Long, blocks_72: Long, blocks_144: Long) {
  require(block_1 > 0 && blocks_2 > 0 && blocks_6 > 0 && blocks_12 > 0 && blocks_36 > 0 && blocks_72 > 0 && blocks_144 > 0, "all feerates must be strictly greater than 0")

  def feePerBlock(target: Int): Long = target match {
    case 1 => block_1
    case 2 => blocks_2
    case t if t <= 6 => blocks_6
    case t if t <= 12 => blocks_12
    case t if t <= 36 => blocks_36
    case t if t <= 72 => blocks_72
    case _ => blocks_144
  }
}

object FeeratesPerKw {
  def apply(feerates: FeeratesPerKB): FeeratesPerKw = FeeratesPerKw(
    block_1 = feerateKB2Kw(feerates.block_1),
    blocks_2 = feerateKB2Kw(feerates.blocks_2),
    blocks_6 = feerateKB2Kw(feerates.blocks_6),
    blocks_12 = feerateKB2Kw(feerates.blocks_12),
    blocks_36 = feerateKB2Kw(feerates.blocks_36),
    blocks_72 = feerateKB2Kw(feerates.blocks_72),
    blocks_144 = feerateKB2Kw(feerates.blocks_144))

  /** Used in tests */
  def single(feeratePerKw: Long): FeeratesPerKw = FeeratesPerKw(
    block_1 = feeratePerKw,
    blocks_2 = feeratePerKw,
    blocks_6 = feeratePerKw,
    blocks_12 = feeratePerKw,
    blocks_36 = feeratePerKw,
    blocks_72 = feeratePerKw,
    blocks_144 = feeratePerKw)
}

