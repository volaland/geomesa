/***********************************************************************
* Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.arrow.vector

import java.io.Closeable

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.complex.NullableMapVector
import org.locationtech.geomesa.arrow.vector.GeometryVector.PointEncoding
import org.locationtech.geomesa.features.arrow.ArrowSimpleFeature
import org.locationtech.geomesa.features.serialization.ObjectType
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

/**
  * Abstraction for using simple features in Arrow vectors
  *
  * @param sft simple feature type
  * @param underlying underlying arrow vector
  * @param dictionaries map of field names to dictionary values, used for dictionary encoding fields.
  *                     All values must be provided up front.
  * @param allocator buffer allocator
  */
class SimpleFeatureVector private (val sft: SimpleFeatureType,
                                   val underlying: NullableMapVector,
                                   val dictionaries: Map[String, ArrowDictionary])
                                  (implicit allocator: BufferAllocator) extends Closeable {

  // TODO user data

  // note: writer creates the map child vectors based on the sft, and should be instantiated before the reader
  val writer = new Writer(this)
  val reader = new Reader(this)

  /**
    * Clear any simple features currently stored in the vector
    */
  def reset(): Unit = {
    // TODO is there a better way to reset the buffer?
    underlying.clear()
    underlying.allocateNewSafe()
  }

  override def close(): Unit = {
    underlying.close()
    writer.arrowWriter.close()
  }

  class Writer(vector: SimpleFeatureVector) {
    private [SimpleFeatureVector] val arrowWriter = vector.underlying.getWriter
    private val idWriter = ArrowAttributeWriter("id", Seq(ObjectType.STRING), classOf[String], vector.underlying, None)
    private val attributeWriters = ArrowAttributeWriter(sft, vector.underlying, dictionaries).toArray

    def set(index: Int, feature: SimpleFeature): Unit = {
      arrowWriter.setPosition(index)
      arrowWriter.start()
      idWriter.apply(index, feature.getID)
      var i = 0
      while (i < attributeWriters.length) {
        attributeWriters(i).apply(index, feature.getAttribute(i))
        i += 1
      }
      arrowWriter.end()
    }

    def setValueCount(count: Int): Unit = {
      arrowWriter.setValueCount(count)
      attributeWriters.foreach(_.setValueCount(count))
    }
  }

  class Reader(vector: SimpleFeatureVector) {
    private val idReader = ArrowAttributeReader("id", Seq(ObjectType.STRING), classOf[String], vector.underlying, None)
    private val attributeReaders = ArrowAttributeReader(sft, vector.underlying, dictionaries).toArray

    def get(index: Int): SimpleFeature = new ArrowSimpleFeature(sft, idReader, attributeReaders, index)

    def getValueCount: Int = vector.underlying.getAccessor.getValueCount
  }
}

object SimpleFeatureVector {

  /**
    * Create a new simple feature vector
    *
    * @param sft simple feature type
    * @param dictionaries map of field names to dictionary values, used for dictionary encoding fields.
    *                     All values must be provided up front.
    * @param allocator buffer allocator
    * @return
    */
  def create(sft: SimpleFeatureType,
             dictionaries: Map[String, ArrowDictionary],
             pointEncoding: PointEncoding = PointEncoding.PARALLEL)
            (implicit allocator: BufferAllocator): SimpleFeatureVector = {
    val underlying = new NullableMapVector(sft.getTypeName, allocator, null, null)
    underlying.allocateNew()
    new SimpleFeatureVector(sft, underlying, dictionaries)
  }

  /**
    * Creates a simple feature vector based on an existing arrow vector
    *
    * @param vector arrow vector
    * @param dictionaries map of field names to dictionary values, used for dictionary encoding fields.
    *                     All values must be provided up front.
    * @param allocator buffer allocator
    * @return
    */
  def wrap(vector: NullableMapVector,
           dictionaries: Map[String, ArrowDictionary])
          (implicit allocator: BufferAllocator): SimpleFeatureVector = {
    import scala.collection.JavaConversions._
    val attributes = vector.getField.getChildren.collect {
      // filter out feature id from attributes
      case field if field.getName != "id" => field.getName
    }
    // TODO user data
    val sft = SimpleFeatureTypes.createType(vector.getField.getName, attributes.mkString(","))
    new SimpleFeatureVector(sft, vector, dictionaries)
  }
}