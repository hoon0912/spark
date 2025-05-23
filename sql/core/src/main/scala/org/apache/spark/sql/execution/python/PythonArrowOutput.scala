/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution.python

import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicBoolean

import scala.jdk.CollectionConverters._

import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamReader

import org.apache.spark.{SparkEnv, TaskContext}
import org.apache.spark.api.python.{BasePythonRunner, PythonWorker, SpecialLengths}
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.{ArrowColumnVector, ColumnarBatch, ColumnVector}

/**
 * A trait that can be mixed-in with [[BasePythonRunner]]. It implements the logic from
 * Python (Arrow) to JVM (output type being deserialized from ColumnarBatch).
 */
private[python] trait PythonArrowOutput[OUT <: AnyRef] { self: BasePythonRunner[_, OUT] =>

  protected def pythonMetrics: Map[String, SQLMetric]

  protected def handleMetadataAfterExec(stream: DataInputStream): Unit = { }

  protected def deserializeColumnarBatch(batch: ColumnarBatch, schema: StructType): OUT

  protected def arrowMaxRecordsPerOutputBatch: Int = SQLConf.get.arrowMaxRecordsPerOutputBatch

  protected def newReaderIterator(
      stream: DataInputStream,
      writer: Writer,
      startTime: Long,
      env: SparkEnv,
      worker: PythonWorker,
      pid: Option[Int],
      releasedOrClosed: AtomicBoolean,
      context: TaskContext): Iterator[OUT] = {

    new ReaderIterator(
      stream, writer, startTime, env, worker, pid, releasedOrClosed, context) {

      private val allocator = ArrowUtils.rootAllocator.newChildAllocator(
        s"stdin reader for $pythonExec", 0, Long.MaxValue)

      private var reader: ArrowStreamReader = _
      private var root: VectorSchemaRoot = _
      private var schema: StructType = _
      private var processor: ArrowOutputProcessor = _

      context.addTaskCompletionListener[Unit] { _ =>
        if (reader != null) {
          reader.close(false)
        }
        allocator.close()
      }

      private var batchLoaded = true

      protected override def handleEndOfDataSection(): Unit = {
        handleMetadataAfterExec(stream)
        super.handleEndOfDataSection()
      }

      protected override def read(): OUT = {
        if (writer.exception.isDefined) {
          throw writer.exception.get
        }
        try {
          if (reader != null && batchLoaded) {
            batchLoaded = processor.loadBatch()
            if (batchLoaded) {
              val batch = processor.produceBatch()
              deserializeColumnarBatch(batch, schema)
            } else {
              processor.close()
              reader.close(false)
              allocator.close()
              // Reach end of stream. Call `read()` again to read control data.
              read()
            }
          } else {
            stream.readInt() match {
              case SpecialLengths.START_ARROW_STREAM =>
                reader = new ArrowStreamReader(stream, allocator)
                root = reader.getVectorSchemaRoot()
                schema = ArrowUtils.fromArrowSchema(root.getSchema())

                if (arrowMaxRecordsPerOutputBatch > 0) {
                  processor = new SliceArrowOutputProcessorImpl(
                    reader, pythonMetrics, arrowMaxRecordsPerOutputBatch)
                } else {
                  processor = new ArrowOutputProcessorImpl(reader, pythonMetrics)
                }

                read()
              case SpecialLengths.TIMING_DATA =>
                handleTimingData()
                read()
              case SpecialLengths.PYTHON_EXCEPTION_THROWN =>
                throw handlePythonException()
              case SpecialLengths.END_OF_DATA_SECTION =>
                handleEndOfDataSection()
                null.asInstanceOf[OUT]
            }
          }
        } catch handleException
      }
    }
  }
}

private[python] trait BasicPythonArrowOutput extends PythonArrowOutput[ColumnarBatch] {
  self: BasePythonRunner[_, ColumnarBatch] =>

  protected def deserializeColumnarBatch(
      batch: ColumnarBatch,
      schema: StructType): ColumnarBatch = batch
}

trait ArrowOutputProcessor {
  def loadBatch(): Boolean
  protected def getRoot: VectorSchemaRoot
  protected def getVectors(root: VectorSchemaRoot): Array[ColumnVector]
  def produceBatch(): ColumnarBatch
  def close(): Unit
}

class ArrowOutputProcessorImpl(reader: ArrowStreamReader, pythonMetrics: Map[String, SQLMetric])
    extends ArrowOutputProcessor {
  protected val root = reader.getVectorSchemaRoot()
  protected val schema: StructType = ArrowUtils.fromArrowSchema(root.getSchema())
  private val vectors: Array[ColumnVector] = root.getFieldVectors().asScala.map { vector =>
    new ArrowColumnVector(vector)
  }.toArray[ColumnVector]

  protected var rowCount = -1

  override def loadBatch(): Boolean = {
    val bytesReadStart = reader.bytesRead()
    val batchLoaded = reader.loadNextBatch()
    if (batchLoaded) {
      rowCount = root.getRowCount
      val bytesReadEnd = reader.bytesRead()
      pythonMetrics("pythonNumRowsReceived") += rowCount
      pythonMetrics("pythonDataReceived") += bytesReadEnd - bytesReadStart
    }
    batchLoaded
  }

  protected override def getRoot: VectorSchemaRoot = root
  protected override def getVectors(root: VectorSchemaRoot): Array[ColumnVector] = vectors
  override def produceBatch(): ColumnarBatch = {
    val batchRoot = getRoot
    val vectors = getVectors(batchRoot)
    val batch = new ColumnarBatch(vectors)
    batch.setNumRows(batchRoot.getRowCount)
    batch
  }
  override def close(): Unit = {
    vectors.foreach(_.close())
    root.close()
  }
}

class SliceArrowOutputProcessorImpl(
    reader: ArrowStreamReader,
    pythonMetrics: Map[String, SQLMetric],
    arrowMaxRecordsPerOutputBatch: Int)
  extends ArrowOutputProcessorImpl(reader, pythonMetrics) {

  private var currentRowIdx = -1
  private var prevRoot: VectorSchemaRoot = null
  private var prevVectors: Array[ColumnVector] = _

  override def produceBatch(): ColumnarBatch = {
    val batchRoot = getRoot

    if (batchRoot != prevRoot) {
      if (prevRoot != null) {
        prevVectors.foreach(_.close())
        prevRoot.close()
      }
      prevRoot = batchRoot
    }

    val vectors = getVectors(batchRoot)
    prevVectors = vectors

    val batch = new ColumnarBatch(vectors)
    batch.setNumRows(batchRoot.getRowCount)
    batch
  }

  override def loadBatch(): Boolean = {
    if (rowCount > 0 && currentRowIdx < rowCount) {
      true
    } else {
      val loaded = super.loadBatch()
      currentRowIdx = 0
      loaded
    }
  }

  protected override def getRoot: VectorSchemaRoot = {
    val remainingRows = rowCount - currentRowIdx
    val rootSlice = if (remainingRows > arrowMaxRecordsPerOutputBatch) {
      root.slice(currentRowIdx, arrowMaxRecordsPerOutputBatch)
    } else {
      root
    }

    currentRowIdx = currentRowIdx + rootSlice.getRowCount

    rootSlice
  }

  protected override def getVectors(root: VectorSchemaRoot): Array[ColumnVector] = {
    root.getFieldVectors.asScala.map { vector =>
      new ArrowColumnVector(vector)
    }.toArray[ColumnVector]
  }

  override def close(): Unit = {
    if (prevRoot != null) {
      prevVectors.foreach(_.close())
      prevRoot.close()
    }
  }
}
