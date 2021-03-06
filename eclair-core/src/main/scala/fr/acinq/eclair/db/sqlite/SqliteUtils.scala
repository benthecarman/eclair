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

package fr.acinq.eclair.db.sqlite

import java.sql.{Connection, ResultSet, Statement}
import java.util.UUID

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.MilliSatoshi
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}

import scala.collection.immutable.Queue

object SqliteUtils {

  /**
   * This helper makes sure statements are correctly closed.
   *
   * @param inTransaction if set to true, all updates in the block will be run in a transaction.
   */
  def using[T <: Statement, U](statement: T, inTransaction: Boolean = false)(block: T => U): U = {
    try {
      if (inTransaction) statement.getConnection.setAutoCommit(false)
      val res = block(statement)
      if (inTransaction) statement.getConnection.commit()
      res
    } catch {
      case t: Exception =>
        if (inTransaction) statement.getConnection.rollback()
        throw t
    } finally {
      if (inTransaction) statement.getConnection.setAutoCommit(true)
      if (statement != null) statement.close()
    }
  }

  /**
   * Several logical databases (channels, network, peers) may be stored in the same physical sqlite database.
   * We keep track of their respective version using a dedicated table. The version entry will be created if
   * there is none but will never be updated here (use setVersion to do that).
   */
  def getVersion(statement: Statement, db_name: String, currentVersion: Int): Int = {
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS versions (db_name TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL)")
    // if there was no version for the current db, then insert the current version
    statement.executeUpdate(s"INSERT OR IGNORE INTO versions VALUES ('$db_name', $currentVersion)")
    // if there was a previous version installed, this will return a different value from current version
    val res = statement.executeQuery(s"SELECT version FROM versions WHERE db_name='$db_name'")
    res.getInt("version")
  }

  /**
   * Updates the version for a particular logical database, it will overwrite the previous version.
   */
  def setVersion(statement: Statement, db_name: String, newVersion: Int) = {
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS versions (db_name TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL)")
    // overwrite the existing version
    statement.executeUpdate(s"UPDATE versions SET version=$newVersion WHERE db_name='$db_name'")
  }

  /**
   * This helper assumes that there is a "data" column available, decodable with the provided codec
   *
   * TODO: we should use an scala.Iterator instead
   */
  def codecSequence[T](rs: ResultSet, codec: Codec[T]): Seq[T] = {
    var q: Queue[T] = Queue()
    while (rs.next()) {
      q = q :+ codec.decode(BitVector(rs.getBytes("data"))).require.value
    }
    q
  }

  /**
   * Obtain an exclusive lock on a sqlite database. This is useful when we want to make sure that only one process
   * accesses the database file (see https://www.sqlite.org/pragma.html).
   *
   * The lock will be kept until the database is closed, or if the locking mode is explicitly reset.
   */
  def obtainExclusiveLock(sqlite: Connection) = synchronized {
    val statement = sqlite.createStatement()
    statement.execute("PRAGMA locking_mode = EXCLUSIVE")
    // we have to make a write to actually obtain the lock
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS dummy_table_for_locking (a INTEGER NOT NULL)")
    statement.executeUpdate("INSERT INTO dummy_table_for_locking VALUES (42)")
  }

  case class ExtendedResultSet(rs: ResultSet) {

    def getBitVectorOpt(columnLabel: String): Option[BitVector] = Option(rs.getBytes(columnLabel)).map(BitVector(_))

    def getByteVector(columnLabel: String): ByteVector = ByteVector(rs.getBytes(columnLabel))

    def getByteVectorNullable(columnLabel: String): ByteVector = {
      val result = rs.getBytes(columnLabel)
      if (rs.wasNull()) ByteVector.empty else ByteVector(result)
    }

    def getByteVector32(columnLabel: String): ByteVector32 = ByteVector32(ByteVector(rs.getBytes(columnLabel)))

    def getByteVector32Nullable(columnLabel: String): Option[ByteVector32] = {
      val bytes = rs.getBytes(columnLabel)
      if (rs.wasNull()) None else Some(ByteVector32(ByteVector(bytes)))
    }

    def getStringNullable(columnLabel: String): Option[String] = {
      val result = rs.getString(columnLabel)
      if (rs.wasNull()) None else Some(result)
    }

    def getLongNullable(columnLabel: String): Option[Long] = {
      val result = rs.getLong(columnLabel)
      if (rs.wasNull()) None else Some(result)
    }

    def getUUIDNullable(label: String): Option[UUID] = {
      val result = rs.getString(label)
      if (rs.wasNull()) None else Some(UUID.fromString(result))
    }

    def getMilliSatoshiNullable(label: String): Option[MilliSatoshi] = {
      val result = rs.getLong(label)
      if (rs.wasNull()) None else Some(MilliSatoshi(result))
    }

  }

  object ExtendedResultSet {
    implicit def conv(rs: ResultSet): ExtendedResultSet = ExtendedResultSet(rs)
  }

}
