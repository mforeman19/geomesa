package org.apache.spark.sql

import com.vividsolutions.jts.geom._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, CodegenFallback, ExprCode}
import org.apache.spark.sql.catalyst.expressions.{GenericInternalRow, LeafExpression, Literal, ScalaUDF, UnsafeArrayData}
import org.apache.spark.sql.catalyst.plans.logical.{Filter, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String
import org.geotools.factory.CommonFactoryFinder
import org.geotools.geometry.jts.{JTS, JTSFactoryFinder}
import org.locationtech.geomesa.compute.spark.GeoMesaRelation
import org.locationtech.geomesa.utils.text.WKTUtils

/**
  * Created by afox on 10/27/16.
  */

object SQLTypes {

  val geomFactory = JTSFactoryFinder.getGeometryFactory
  @transient val ff = CommonFactoryFinder.getFilterFactory2

  val PointType = new PointUDT
  val GeometryType = new GeometryUDT

  UDTRegistration.register(classOf[Point].getCanonicalName, classOf[PointUDT].getCanonicalName)
  UDTRegistration.register(classOf[Polygon].getCanonicalName, classOf[PolygonUDT].getCanonicalName)
  UDTRegistration.register(classOf[Geometry].getCanonicalName, classOf[GeometryUDT].getCanonicalName)

  val ST_Contains: (Point, Geometry) => Boolean = (p, geom) => geom.contains(p)
  val ST_Envelope:  Polygon => Polygon = p => p.getEnvelope.asInstanceOf[Polygon]
  val ST_MakeBox2D: (Point, Point) => Polygon = (ll, ur) => JTS.toGeometry(new Envelope(ll.getX, ur.getX, ll.getY, ur.getY))

  // TODO: optimize when used as a literal
  // e.g. select * from feature where st_contains(geom, geomFromText('POLYGON((....))'))
  // should not deserialize the POLYGON for every call
  val ST_GeomFromWKT: String => Geometry = s => WKTUtils.read(s)

  def registerFunctions(sqlContext: SQLContext): Unit = {
    sqlContext.udf.register("st_geomFromWKT"   , ST_GeomFromWKT)
    sqlContext.udf.register("st_contains"      , ST_Contains)
    sqlContext.udf.register("st_within"        , ST_Contains) // TODO: is contains different than within?
    sqlContext.udf.register("st_envelope"      , ST_Envelope)
    sqlContext.udf.register("st_makeBox2D"     , ST_MakeBox2D)
  }


  object STContainsRule extends Rule[LogicalPlan] {
    override def apply(plan: LogicalPlan): LogicalPlan = {
      plan.transform {
        case Filter(ScalaUDF(fn, DataTypes.BooleanType, Seq(_, ScalaUDF(_, _, Seq(Literal(poly: UTF8String, DataTypes.StringType)), _)), _), lr @ LogicalRelation(gmRel: GeoMesaRelation, _, _))
          if fn.equals(ST_Contains) => {
          // replace the filter plan with a rel plan with a set filter
          val geomDescriptor = gmRel.sft.getGeometryDescriptor.getLocalName
          val cqlFilter = ff.within(ff.property(geomDescriptor), ff.literal(WKTUtils.read(poly.toString)))
          // need to maintain expectedOutputAttributes so identifiers don't change in projections
          lr.copy(expectedOutputAttributes = Some(lr.output), relation = gmRel.copy(filt = Some(cqlFilter)))
        }
      }
    }
  }

  case class GeometryLiteral(geom: Geometry) extends LeafExpression  with CodegenFallback {

    override def foldable: Boolean = true

    override def nullable: Boolean = true

    override def eval(input: InternalRow): Any = {
      InternalRow(Array(geom))
    }

    override def dataType: DataType = GeometryType

    override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
      ctx.addReferenceObj("geom", geom, classOf[Geometry].getCanonicalName)
      ev.copy(code = s"final com.vividsolutions.jts.geom.Geometry ${ev.value} = geom;", isNull = "false")
    }

  }

  object FoldConstantGeometryRule extends Rule[LogicalPlan] {
    override def apply(plan: LogicalPlan): LogicalPlan = {
      plan.transform {
        case q: LogicalPlan => q.transformExpressionsDown {
          case ScalaUDF(ST_GeomFromWKT, GeometryType, Seq(Literal(wkt, DataTypes.StringType)), Seq(DataTypes.StringType)) => {
            val geom = ST_GeomFromWKT(wkt.asInstanceOf[UTF8String].toString)
            GeometryLiteral(geom)
          }
          case t =>
            t
        }
      }
    }
  }

  def registerOptimizations(sqlContext: SQLContext): Unit = {
    Seq(STContainsRule, FoldConstantGeometryRule).foreach { r =>
      if(!sqlContext.experimental.extraOptimizations.contains(r))
        sqlContext.experimental.extraOptimizations ++= Seq(r)
    }

    Seq.empty[Strategy].foreach { s =>
      if(!sqlContext.experimental.extraStrategies.contains(s))
        sqlContext.experimental.extraStrategies ++= Seq(s)
    }
  }

  def init(sqlContext: SQLContext): Unit = {
    registerFunctions(sqlContext)
    registerOptimizations(sqlContext)
  }
}

private [spark] class PointUDT extends UserDefinedType[Point] {

  override def sqlType: DataType = StructType(
    Seq(
      StructField("type", DataTypes.ByteType),
      StructField("geometry", DataTypes.createArrayType(DataTypes.DoubleType))
    )
  )

  override def serialize(obj: Point): InternalRow = {
    new GenericInternalRow(Array(1.asInstanceOf[Byte], UnsafeArrayData.fromPrimitiveArray(Array(obj.getX, obj.getY))))
  }

  override def userClass: Class[Point] = classOf[Point]

  override def deserialize(datum: Any): Point = {
    val ir = datum.asInstanceOf[InternalRow]
    val coords = ir.getArray(1).toDoubleArray()
    SQLTypes.geomFactory.createPoint(new Coordinate(coords(0), coords(1)))
  }

}

private [spark] class PolygonUDT extends UserDefinedType[Polygon] {

  override def sqlType: DataType = StructType(
    Seq(
      StructField("type", DataTypes.ByteType),
      StructField("geometry", DataTypes.createArrayType(DataTypes.DoubleType))
    )
  )

  override def serialize(obj: Polygon): InternalRow = {
    // only simple polys for now
    val coords = obj.getCoordinates.map { c => Array(c.x, c.y) }.reduce { (l, r) => l ++ r }
    new GenericInternalRow(Array(2.asInstanceOf[Byte],
      UnsafeArrayData.fromPrimitiveArray(coords)))
  }

  override def userClass: Class[Polygon] = classOf[Polygon]

  override def deserialize(datum: Any): Polygon = {
    val ir = datum.asInstanceOf[InternalRow]
    val coords = ir.getArray(1).toDoubleArray().grouped(2).map { case Array(l, r) => new Coordinate(l, r) }
    SQLTypes.geomFactory.createPolygon(coords.toArray)
  }

}

private [spark] class GeometryUDT extends UserDefinedType[Geometry] {
  override def sqlType: DataType = StructType(
    Seq(
      StructField("type", DataTypes.ByteType),
      StructField("geometry", DataTypes.createArrayType(DataTypes.DoubleType))
    )
  )

  override def serialize(obj: Geometry): InternalRow = {
    obj.getGeometryType match {
      case "Point"   => serializePoint(obj.asInstanceOf[Point])
      case "Polygon" => serializePoly(obj.asInstanceOf[Polygon])
    }
  }

  def serializePoint(p: Point): InternalRow = {
    new GenericInternalRow(Array(1.asInstanceOf[Byte], UnsafeArrayData.fromPrimitiveArray(Array(p.getX, p.getY))))
  }

  def serializePoly(obj: Polygon): InternalRow = {
    // only simple polys for now
    val coords = obj.getCoordinates.map { c => Array(c.x, c.y) }.reduce { (l, r) => l ++ r }
    new GenericInternalRow(Array(2.asInstanceOf[Byte],
      UnsafeArrayData.fromPrimitiveArray(coords)))
  }

  override def userClass: Class[Geometry] = classOf[Geometry]

  override def deserialize(datum: Any): Geometry = {
    val ir = datum.asInstanceOf[InternalRow]
    ir.getByte(0) match {
      case 1 => deserializePoint(ir)
      case 2 => deserializePoly(ir)
    }
  }

  def deserializePoint(ir: InternalRow): Point = {
    val coords = ir.getArray(1).toDoubleArray()
    SQLTypes.geomFactory.createPoint(new Coordinate(coords(0), coords(1)))
  }

  def deserializePoly(ir: InternalRow): Polygon = {
    val coords = ir.getArray(1).toDoubleArray().grouped(2).map { case Array(l, r) => new Coordinate(l, r) }
    SQLTypes.geomFactory.createPolygon(coords.toArray)
  }

}

case object GeometryUDT extends GeometryUDT