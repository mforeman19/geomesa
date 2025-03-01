/***********************************************************************
 * Copyright (c) 2013-2019 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.spark


import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.StructType
import org.geotools.data.DataStore
import org.geotools.util.factory.Hints
import org.locationtech.geomesa.spark.GeoMesaSparkSQL._
import org.locationtech.geomesa.utils.geotools.{SftArgResolver, SftArgs, SimpleFeatureTypes}
import org.locationtech.geomesa.utils.io.WithStore
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

// Spark DataSource for GeoMesa
// enables loading a GeoMesa DataFrame as
// {{
// val df = spark.read
//   .format("geomesa")
//   .option(GM.instanceIdParam.getName, "mycloud")
//   .option(GM.userParam.getName, "user")
//   .option(GM.passwordParam.getName, "password")
//   .option(GM.tableNameParam.getName, "sparksql")
//   .option(GM.mockParam.getName, "true")
//   .option("geomesa.feature", "chicago")
//   .load()
// }}
class GeoMesaDataSource extends DataSourceRegister
    with RelationProvider with SchemaRelationProvider with CreatableRelationProvider with LazyLogging {

  import CaseInsensitiveMapFix._

  import scala.collection.JavaConverters._

  override def shortName(): String = "geomesa"

  override def createRelation(sqlContext: SQLContext, parameters: Map[String, String]): BaseRelation = {
    SQLTypes.init(sqlContext)

    // TODO: Need different ways to retrieve sft
    //  GEOMESA-1643 Add method to lookup SFT to RDD Provider
    //  Below the details of the Converter RDD Provider and Providers which are backed by GT DSes are leaking through
    val sft = WithStore[DataStore](parameters) { ds =>
      if (ds != null) {
        ds.getSchema(parameters(GEOMESA_SQL_FEATURE))
      } else if (parameters.contains(GEOMESA_SQL_FEATURE) && parameters.contains("geomesa.sft")) {
        SimpleFeatureTypes.createType(parameters(GEOMESA_SQL_FEATURE), parameters("geomesa.sft"))
      } else {
        SftArgResolver.getArg(SftArgs(parameters(GEOMESA_SQL_FEATURE), parameters(GEOMESA_SQL_FEATURE))) match {
          case Right(s) => s
          case Left(e) => throw new IllegalArgumentException("Could not resolve simple feature type", e)
        }
      }
    }

    logger.trace(s"Creating GeoMesa Relation with sft : $sft")

    val schema = SparkUtils.createStructType(sft)
    GeoMesaRelation(sqlContext, sft, schema, parameters)
  }

  // JNH: Q: Why doesn't this method have the call to SQLTypes.init(sqlContext)?
  override def createRelation(sqlContext: SQLContext, parameters: Map[String, String], schema: StructType): BaseRelation = {
    val sft = WithStore[DataStore](parameters)(_.getSchema(parameters(GEOMESA_SQL_FEATURE)))
    GeoMesaRelation(sqlContext, sft, schema, parameters)
  }

  @deprecated("Use SparkUtils.createFeatureType")
  def structType2SFT(struct: StructType, name: String): SimpleFeatureType =
    SparkUtils.createFeatureType(name, struct)

  override def createRelation(sqlContext: SQLContext, mode: SaveMode, parameters: Map[String, String], data: DataFrame): BaseRelation = {
    val newFeatureName = parameters(GEOMESA_SQL_FEATURE)
    val sft: SimpleFeatureType = SparkUtils.createFeatureType(newFeatureName, data.schema)

    WithStore[DataStore](parameters) { ds =>
      if (ds.getTypeNames.contains(newFeatureName)) {
        val existing = ds.getSchema(newFeatureName)
        if (!compatible(existing, sft)) {
          throw new IllegalStateException(
            "The dataframe is not compatible with the existing schema in the datastore:" +
                s"\n  Dataframe schema: ${SimpleFeatureTypes.encodeType(sft)}" +
                s"\n  Datastore schema: ${SimpleFeatureTypes.encodeType(existing)}")
        }
      } else {
        sft.getUserData.put("override.reserved.words", java.lang.Boolean.TRUE)
        ds.createSchema(sft)
      }
    }

    val structType = if (data.queryExecution == null) { SparkUtils.createStructType(sft) } else { data.schema }

    val rddToSave: RDD[SimpleFeature] = data.rdd.mapPartitions { iterRow =>
      val sft = WithStore[DataStore](parameters)(_.getSchema(newFeatureName))
      val mappings = SparkUtils.rowsToFeatures(sft, structType)
      iterRow.map { row =>
        val sf = mappings.apply(row)
        sf.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
        sf
      }
    }

    GeoMesaSpark(parameters).save(rddToSave, parameters, newFeatureName)

    GeoMesaRelation(sqlContext, sft, data.schema, parameters)
  }

  // are schemas compatible? we're flexible with order, but require the same number, names and types
  private def compatible(sft: SimpleFeatureType, dataframe: SimpleFeatureType): Boolean = {
    sft.getAttributeCount == dataframe.getAttributeCount && sft.getAttributeDescriptors.asScala.forall { ad =>
      val df = dataframe.getDescriptor(ad.getLocalName)
      df != null && ad.getType.getBinding.isAssignableFrom(df.getType.getBinding)
    }
  }
}
