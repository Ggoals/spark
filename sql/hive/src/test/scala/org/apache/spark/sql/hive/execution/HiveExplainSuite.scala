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

package org.apache.spark.sql.hive.execution

import org.apache.spark.sql.QueryTest
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.parser.ParseException
import org.apache.spark.sql.hive.test.TestHiveSingleton
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SQLTestUtils

/**
 * A set of tests that validates support for Hive Explain command.
 */
class HiveExplainSuite extends QueryTest with SQLTestUtils with TestHiveSingleton {
  import testImplicits._

  test("show cost in explain command") {
    val explainCostCommand = "EXPLAIN COST  SELECT * FROM src"
    // For readability, we only show optimized plan and physical plan in explain cost command
    checkKeywordsExist(sql(explainCostCommand),
      "Optimized Logical Plan", "Physical Plan")
    checkKeywordsNotExist(sql(explainCostCommand),
      "Parsed Logical Plan", "Analyzed Logical Plan")

    withSQLConf(SQLConf.CBO_ENABLED.key -> "true") {
      // Only has sizeInBytes before ANALYZE command
      checkKeywordsExist(sql(explainCostCommand), "sizeInBytes")
      checkKeywordsNotExist(sql(explainCostCommand), "rowCount")

      // Has both sizeInBytes and rowCount after ANALYZE command
      sql("ANALYZE TABLE src COMPUTE STATISTICS")
      checkKeywordsExist(sql(explainCostCommand), "sizeInBytes", "rowCount")
    }

    spark.sessionState.catalog.refreshTable(TableIdentifier("src"))

    withSQLConf(SQLConf.CBO_ENABLED.key -> "false") {
      // Don't show rowCount if cbo is disabled
      checkKeywordsExist(sql(explainCostCommand), "sizeInBytes")
      checkKeywordsNotExist(sql(explainCostCommand), "rowCount")
    }

    // No statistics information if "cost" is not specified
    checkKeywordsNotExist(sql("EXPLAIN  SELECT * FROM src "), "sizeInBytes", "rowCount")
  }

  test("explain extended command") {
    checkKeywordsExist(sql(" explain   select * from src where key=123 "),
                   "== Physical Plan ==",
                   "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe")

    checkKeywordsNotExist(sql(" explain   select * from src where key=123 "),
                   "== Parsed Logical Plan ==",
                   "== Analyzed Logical Plan ==",
                   "== Optimized Logical Plan ==",
                   "Owner",
                   "Database",
                   "Created",
                   "Last Access",
                   "Type",
                   "Provider",
                   "Properties",
                   "Statistics",
                   "Location",
                   "Serde Library",
                   "InputFormat",
                   "OutputFormat",
                   "Partition Provider",
                   "Schema"
    )

    checkKeywordsExist(sql(" explain   extended select * from src where key=123 "),
                   "== Parsed Logical Plan ==",
                   "== Analyzed Logical Plan ==",
                   "== Optimized Logical Plan ==",
                   "== Physical Plan ==")
  }

  test("explain create table command") {
    checkKeywordsExist(sql("explain create table temp__b as select * from src limit 2"),
                   "== Physical Plan ==",
                   "InsertIntoHiveTable",
                   "Limit",
                   "src")

    checkKeywordsExist(sql("explain extended create table temp__b as select * from src limit 2"),
      "== Parsed Logical Plan ==",
      "== Analyzed Logical Plan ==",
      "== Optimized Logical Plan ==",
      "== Physical Plan ==",
      "CreateHiveTableAsSelect",
      "InsertIntoHiveTable",
      "Limit",
      "src")

    checkKeywordsExist(sql(
      """
        | EXPLAIN EXTENDED CREATE TABLE temp__b
        | ROW FORMAT SERDE "org.apache.hadoop.hive.serde2.columnar.ColumnarSerDe"
        | WITH SERDEPROPERTIES("serde_p1"="p1","serde_p2"="p2")
        | STORED AS RCFile
        | TBLPROPERTIES("tbl_p1"="p11", "tbl_p2"="p22")
        | AS SELECT * FROM src LIMIT 2
      """.stripMargin),
      "== Parsed Logical Plan ==",
      "== Analyzed Logical Plan ==",
      "== Optimized Logical Plan ==",
      "== Physical Plan ==",
      "CreateHiveTableAsSelect",
      "InsertIntoHiveTable",
      "Limit",
      "src")
  }

  test("SPARK-17409: The EXPLAIN output of CTAS only shows the analyzed plan") {
    withTempView("jt") {
      val ds = (1 to 10).map(i => s"""{"a":$i, "b":"str$i"}""").toDS()
      spark.read.json(ds).createOrReplaceTempView("jt")
      val outputs = sql(
        s"""
           |EXPLAIN EXTENDED
           |CREATE TABLE t1
           |AS
           |SELECT * FROM jt
         """.stripMargin).collect().map(_.mkString).mkString

      val shouldContain =
        "== Parsed Logical Plan ==" :: "== Analyzed Logical Plan ==" :: "Subquery" ::
        "== Optimized Logical Plan ==" :: "== Physical Plan ==" ::
        "CreateHiveTableAsSelect" :: "InsertIntoHiveTable" :: "jt" :: Nil
      for (key <- shouldContain) {
        assert(outputs.contains(key), s"$key doesn't exist in result")
      }

      val physicalIndex = outputs.indexOf("== Physical Plan ==")
      assert(outputs.substring(physicalIndex).contains("Subquery"),
        "Physical Plan should contain SubqueryAlias since the query should not be optimized")
    }
  }

  test("EXPLAIN CODEGEN command") {
    checkKeywordsExist(sql("EXPLAIN CODEGEN SELECT 1"),
      "WholeStageCodegen",
      "Generated code:",
      "/* 001 */ public Object generate(Object[] references) {",
      "/* 002 */   return new GeneratedIterator(references);",
      "/* 003 */ }"
    )

    checkKeywordsNotExist(sql("EXPLAIN CODEGEN SELECT 1"),
      "== Physical Plan =="
    )

    intercept[ParseException] {
      sql("EXPLAIN EXTENDED CODEGEN SELECT 1")
    }
  }

  test("SPARK-23021 AnalysisBarrier should not cut off explain output for parsed logical plans") {
    val df = Seq((1, 1)).toDF("a", "b").groupBy("a").count().limit(1)
    val outputStream = new java.io.ByteArrayOutputStream()
    Console.withOut(outputStream) {
      df.explain(true)
    }
    assert(outputStream.toString.replaceAll("""#\d+""", "#0").contains(
      s"""== Parsed Logical Plan ==
         |GlobalLimit 1
         |+- LocalLimit 1
         |   +- AnalysisBarrier
         |         +- Aggregate [a#0], [a#0, count(1) AS count#0L]
         |            +- Project [_1#0 AS a#0, _2#0 AS b#0]
         |               +- LocalRelation [_1#0, _2#0]
         |""".stripMargin))
  }
}
