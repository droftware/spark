package spark.network

import java.nio.ByteBuffer

import scala.collection.mutable.ArrayBuffer

import spark.storage.BlockManager


private[spark]
class BufferMessage(id_ : Int, val buffers: ArrayBuffer[ByteBuffer], var ackId: Int)
  extends Message(Message.BUFFER_MESSAGE, id_) {

  val initialSize = currentSize()
  var gotChunkForSendingOnce = false

  def size = initialSize

  def currentSize() = {
    if (buffers == null || buffers.isEmpty) {
      0
    } else {
      buffers.map(_.remaining).reduceLeft(_ + _)
    }
  }

  def getChunkForSending(maxChunkSize: Int): Option[MessageChunk] = {
    if (maxChunkSize <= 0) {
      throw new Exception("Max chunk size is " + maxChunkSize)
    }

    if (size == 0 && gotChunkForSendingOnce == false) {
      val newChunk = new MessageChunk(
        new MessageChunkHeader(typ, id, 0, 0, ackId, senderAddress), null)
      gotChunkForSendingOnce = true
      return Some(newChunk)
    }

    while(!buffers.isEmpty) {
      val buffer = buffers(0)
      if (buffer.remaining == 0) {
        BlockManager.dispose(buffer)
        buffers -= buffer
      } else {
        val newBuffer = if (buffer.remaining <= maxChunkSize) {
          buffer.duplicate()
        } else {
          buffer.slice().limit(maxChunkSize).asInstanceOf[ByteBuffer]
        }
        buffer.position(buffer.position + newBuffer.remaining)
        val newChunk = new MessageChunk(new MessageChunkHeader(
            typ, id, size, newBuffer.remaining, ackId, senderAddress), newBuffer)
        gotChunkForSendingOnce = true
        return Some(newChunk)
      }
    }
    None
  }

  def getChunkForReceiving(chunkSize: Int): Option[MessageChunk] = {
    // STRONG ASSUMPTION: BufferMessage created when receiving data has ONLY ONE data buffer
    if (buffers.size > 1) {
      throw new Exception("Attempting to get chunk from message with multiple data buffers")
    }
    val buffer = buffers(0)
    if (buffer.remaining > 0) {
      if (buffer.remaining < chunkSize) {
        throw new Exception("Not enough space in data buffer for receiving chunk")
      }
      val newBuffer = buffer.slice().limit(chunkSize).asInstanceOf[ByteBuffer]
      buffer.position(buffer.position + newBuffer.remaining)
      val newChunk = new MessageChunk(new MessageChunkHeader(
          typ, id, size, newBuffer.remaining, ackId, senderAddress), newBuffer)
      return Some(newChunk)
    }
    None
  }

  def flip() {
    buffers.foreach(_.flip)
  }

  def hasAckId() = (ackId != 0)

  def isCompletelyReceived() = !buffers(0).hasRemaining

  override def toString = {
    if (hasAckId) {
      "BufferAckMessage(aid = " + ackId + ", id = " + id + ", size = " + size + ")"
    } else {
      "BufferMessage(id = " + id + ", size = " + size + ")"
    }
  }
}