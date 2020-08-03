package org.neo4j.spark.service

import java.util.Collections

import org.apache.spark.sql.types.{DataType, DataTypes, StructField, StructType}
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.renderer.Renderer
import org.neo4j.driver.exceptions.ClientException
import org.neo4j.driver.Session
import org.neo4j.driver.summary.QueryType
import org.neo4j.spark.util.Neo4jUtil
import org.neo4j.spark.{DriverCache, Neo4jOptions, Neo4jQuery}

import collection.JavaConverters._
import scala.collection.mutable

class SchemaService(private val options: Neo4jOptions, private val jobId: String) extends AutoCloseable {

  import SchemaService._

  import org.neo4j.spark.QueryType._

  private val cypherRenderer = Renderer.getDefaultRenderer

  private val driverCache: DriverCache = new DriverCache(options.connection, jobId)

  private val session: Session = driverCache.getOrCreate().session(options.session.toNeo4jSession)

  private def cypherToSparkType(cypherType: String): DataType = {
    cypherType match {
      case "Boolean" => DataTypes.BooleanType
      case "String" => DataTypes.StringType
      case "Long" => DataTypes.LongType
      case "Double" => DataTypes.DoubleType
      case "Point" | "InternalPoint2D" | "InternalPoint3D" => pointType
      case "LocalDateTime"  | "DateTime" | "ZonedDateTime" => DataTypes.TimestampType
      case "OffsetTime" | "Time" | "LocalTime" => timeType
      case "LocalDate" | "Date" => DataTypes.DateType
      case "Duration" | "InternalIsoDuration" => durationType
      case "StringArray" => DataTypes.createArrayType(DataTypes.StringType)
      case "LongArray" => DataTypes.createArrayType(DataTypes.LongType)
      case "DoubleArray" => DataTypes.createArrayType(DataTypes.DoubleType)
      case "BooleanArray" => DataTypes.createArrayType(DataTypes.BooleanType)
      case "PointArray" | "InternalPoint2DArray" | "InternalPoint3DArray" => DataTypes.createArrayType(pointType)
      case "LocalDateTimeArray" | "DateTimeArray" | "ZonedDateTimeArray" => DataTypes.createArrayType(DataTypes.TimestampType)
      case "OffsetTimeArray" | "TimeArray" | "LocalTimeArray" => DataTypes.createArrayType(timeType)
      case "LocalDateArray" | "DateArray" => DataTypes.createArrayType(DataTypes.DateType)
      case "DurationArray" | "InternalIsoDurationArray" => DataTypes.createArrayType(durationType)
      case _ => DataTypes.StringType
    }
  }

  def queryForNode(): StructType = {
    var structFields: mutable.Buffer[StructField] = (try {
      retrieveSchemaFromApoc
    } catch {
      case e: ClientException =>
        e.code match {
          case "Neo.ClientError.Procedure.ProcedureNotFound" => retrieveSchema
        }
    })
      .sortBy(t => t.name)

    structFields += StructField(Neo4jQuery.INTERNAL_LABELS_FIELD, DataTypes.createArrayType(DataTypes.StringType), nullable = true)
    structFields += StructField(Neo4jQuery.INTERNAL_ID_FIELD, DataTypes.LongType, nullable = false)
    StructType(structFields.reverse)
  }

  private def retrieveSchemaFromApoc: mutable.Buffer[StructField] = {
    session.run(
      "CALL apoc.meta.nodeTypeProperties({ includeLabels: $labels })",
      Collections.singletonMap[String, Object]("labels", options.query.value.split(":").toSeq.asJava)
    ).list.asScala.map(record => {
      StructField(record.get("propertyName").asString, cypherToSparkType(record.get("propertyTypes").asList.get(0).toString))
    })
  }

  private def retrieveSchema: mutable.Buffer[StructField] = {
    val node = Cypher.node(options.query.value).named(Neo4jQuery.NODE_ALIAS)
    session.run(
      cypherRenderer.render(Cypher.`match`(node).returning(node).limit(options.query.schemaFlattenLimit).build())
    ).list.asScala.flatMap(record => {
      record.get(Neo4jQuery.NODE_ALIAS).asNode.asMap.asScala.toList
    })
      .groupBy(_._1)
      .map(t => {
        val value = t._2.head._2
        StructField(t._1, cypherToSparkType(value match {
          case l: java.util.List[Any] => s"${l.get(0).getClass.getSimpleName}Array"
          case _ => value.getClass.getSimpleName
        }))
      }).toBuffer
  }

  def queryForRelationship(): StructType = StructType(Array.empty[StructField])

  def query(): StructType = StructType(Array.empty[StructField])

  def fromQuery(): StructType = {
    options.query.queryType match {
      case LABELS => queryForNode()
      case RELATIONSHIP => queryForRelationship()
      case QUERY => query()
    }
  }

  def isReadQuery(query: String): Boolean = {
    val queryType = session.run(s"EXPLAIN $query").consume().queryType()
    queryType == QueryType.READ_ONLY || queryType == QueryType.SCHEMA_WRITE
  }

  override def close(): Unit = {
    Neo4jUtil.closeSafety(session)
  }
}

object SchemaService {
  val POINT_TYPE_2D = "point-2d"
  val POINT_TYPE_3D = "point-3d"

  val TIME_TYPE_OFFSET = "offset-time"
  val TIME_TYPE_LOCAL = "local-time"

  val DURATION_TYPE = "duration"

  val durationType: DataType = DataTypes.createStructType(Array(
    DataTypes.createStructField("type", DataTypes.StringType, false),
    DataTypes.createStructField("months", DataTypes.LongType, false),
    DataTypes.createStructField("days", DataTypes.LongType, false),
    DataTypes.createStructField("seconds", DataTypes.LongType, false),
    DataTypes.createStructField("nanoseconds", DataTypes.IntegerType, false),
    DataTypes.createStructField("value", DataTypes.StringType, false)
  ))

  val pointType: DataType = DataTypes.createStructType(Array(
    DataTypes.createStructField("type", DataTypes.StringType, false),
    DataTypes.createStructField("srid", DataTypes.IntegerType, false),
    DataTypes.createStructField("x", DataTypes.DoubleType, false),
    DataTypes.createStructField("y", DataTypes.DoubleType, false),
    DataTypes.createStructField("z", DataTypes.DoubleType, true)
  ))

  val timeType: DataType = DataTypes.createStructType(Array(
    DataTypes.createStructField("type", DataTypes.StringType, false),
    DataTypes.createStructField("value", DataTypes.StringType, false)
  ))
}