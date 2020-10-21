package org.sunbird.job.functions

import java.util

import com.datastax.driver.core.Row
import com.datastax.driver.core.querybuilder.QueryBuilder
import org.apache.commons.collections.{CollectionUtils, MapUtils}
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper
import org.sunbird.job.Metrics
import org.sunbird.job.cache.DataCache
import org.sunbird.job.task.{CertificatePreProcessorConfig, CertificatePreProcessorStreamTask}
import org.sunbird.job.util.CassandraUtil

import scala.collection.JavaConverters._

object CertificateService {

  lazy private val mapper: ObjectMapper = new ObjectMapper()

  def readCertTemplates(edata: util.Map[String, AnyRef], config: CertificatePreProcessorConfig)(implicit metrics: Metrics, @transient cassandraUtil: CassandraUtil): util.Map[String, AnyRef] = {
    println("readCertTemplates called : ")
    val dataToSelect = Map(config.courseBatchPrimaryKey.head -> edata.get(config.batchId).asInstanceOf[String],
      config.courseBatchPrimaryKey(1) -> edata.get(config.courseId).asInstanceOf[String])
    val selectQuery = prepareReadQuery(config.dbKeyspace, config.dbBatchTable, dataToSelect)
    println("readCertTemplates query : " + selectQuery)
    val rows = cassandraUtil.find(selectQuery)
    metrics.incCounter(config.dbReadCount)
    if (CollectionUtils.isNotEmpty(rows))
      rows.asScala.head.getObject(config.certTemplates).asInstanceOf[util.Map[String, AnyRef]]
    else
      throw new Exception("Certificate template is not available : " + selectQuery)
  }

  def readUserIdsFromDb(enrollmentCriteria: util.Map[String, AnyRef], edata: util.Map[String, AnyRef], config: CertificatePreProcessorConfig)(implicit metrics: Metrics, @transient cassandraUtil: CassandraUtil): util.List[Row] = {
    println("readUserIdsFromDb called : " + enrollmentCriteria)
    val dataToSelect = Map(config.userEnrolmentsPrimaryKey.head -> edata.get(config.userIds).asInstanceOf[util.ArrayList[String]],
      config.userEnrolmentsPrimaryKey(1) -> edata.get(config.courseId).asInstanceOf[String],
      config.userEnrolmentsPrimaryKey(2) -> edata.get(config.batchId).asInstanceOf[String]).++(enrollmentCriteria.asScala)
    val selectQuery = prepareReadQuery(config.dbKeyspace, config.dbBatchTable, dataToSelect)
    println("readUserIdsFromDb : Enroll User read query : " + selectQuery)
    val rows = cassandraUtil.find(selectQuery)
    metrics.incCounter(config.dbReadCount)
    if (CollectionUtils.isNotEmpty(rows))
      rows
    else
      throw new Exception("User read failed : " + selectQuery)
  }

  def fetchAssessedUsersFromDB(edata: util.Map[String, AnyRef], config: CertificatePreProcessorConfig)(implicit metrics: Metrics, @transient cassandraUtil: CassandraUtil): util.List[Row] = {
    val query = "SELECT user_id, max(total_score) as score, total_max_score FROM " + config.dbKeyspace +
      "." + config.dbAssessmentAggregator + " where course_id='" + edata.get(config.courseId).asInstanceOf[String] + "' AND batch_id='" +
      edata.get(config.batchId).asInstanceOf[String] + "' " + "GROUP BY course_id,batch_id,user_id,content_id ORDER BY batch_id,user_id,content_id;"
    println("fetchAssessedUsersFromDB called query : " + query)
    val rows = cassandraUtil.find(query)
    metrics.incCounter(config.dbReadCount)
    if (CollectionUtils.isNotEmpty(rows))
      rows
    else
      throw new Exception("Assess User read failed : " + query)
  }

  def readUserCertificate(edata: util.Map[String, AnyRef], config: CertificatePreProcessorConfig)(implicit metrics: Metrics, @transient cassandraUtil: CassandraUtil) {
    val selectQuery = QueryBuilder.select().all().from(config.dbKeyspace, config.dbUserTable)
    selectQuery.where.and(QueryBuilder.eq(config.userEnrolmentsPrimaryKey.head, edata.get(config.userId).asInstanceOf[String])).
      and(QueryBuilder.eq(config.userEnrolmentsPrimaryKey(1), edata.get(config.courseId).asInstanceOf[String])).
      and(QueryBuilder.eq(config.userEnrolmentsPrimaryKey(2), edata.get(config.batchId).asInstanceOf[String]))
    val rows = cassandraUtil.find(selectQuery.toString)
    metrics.incCounter(config.dbReadCount)
    if (CollectionUtils.isNotEmpty(rows)) {
      edata.put(config.issued_certificates, rows.asScala.head.getObject(config.issued_certificates))
      edata.put(config.issuedDate, rows.asScala.head.getDate(config.completedOn))
    } else {
      throw new Exception("User : " + edata.get(config.userId) + " is not enrolled for batch :  "
        + edata.get(config.batchId) + " and course : " + edata.get(config.courseId))
    }
  }

  private def prepareReadQuery(keyspace: String, table: String, dataToSelect: Map[String, AnyRef]): String = {
    val query = QueryBuilder.select().all().from(keyspace, table)
    dataToSelect.map(entry => {
      if (entry._2.isInstanceOf[util.ArrayList])
        query.where.and(QueryBuilder.in(entry._1, entry._2))
      else
        query.where.and(QueryBuilder.eq(entry._1, entry._2))
    })
    query.toString
  }

  def readContent(courseId: String, cache: DataCache, config: CertificatePreProcessorConfig): util.Map[String, AnyRef] = {
    val httpResponse = CertificatePreProcessorStreamTask.httpUtil.get(config.lmsBaseUrl + "/content/v3/read/" + courseId)
    if (httpResponse.status == 200) {
      println("Content read success: " + httpResponse.body)
      val response = mapper.readValue(httpResponse.body, classOf[util.Map[String, AnyRef]])
      val result = response.getOrDefault("result", new util.HashMap()).asInstanceOf[util.Map[String, AnyRef]]
      val content = result.getOrDefault("content", new util.HashMap()).asInstanceOf[util.Map[String, AnyRef]]
      if (MapUtils.isEmpty(content)) {
        throw new Exception("Content is empty for courseId : " + courseId)
      }
      content
    } else {
      throw new Exception("Content read failed for courseId : " + courseId + " " + httpResponse.status + " :: " + httpResponse.body)
      null
    }
  }

  def getUserDetails(userId: String, config: CertificatePreProcessorConfig): util.Map[String, AnyRef] = {
    val httpRequest = s"""{"request":{"filters":{"identifier":"${userId}"},"fields":["firstName", "lastName", "userName", "rootOrgName", "rootOrgId","maskedPhone"]}}"""
    val httpResponse = CertificatePreProcessorStreamTask.httpUtil.post(config.searchBaseUrl + "/private/user/v1/search", httpRequest)
    if (httpResponse.status == 200) {
      println("User search success: " + httpResponse.body)
      val response = mapper.readValue(httpResponse.body, classOf[util.Map[String, AnyRef]])
      val result = response.getOrDefault("result", new util.HashMap()).asInstanceOf[util.Map[String, AnyRef]]
      val contents = result.getOrDefault("content", new util.ArrayList[util.Map[String, AnyRef]]()).asInstanceOf[util.List[util.Map[String, AnyRef]]]
      val userDetails = contents.get(0)
      if (MapUtils.isEmpty(userDetails)) {
        throw new Exception("User not found for userId : " + userId)
      }
      userDetails
    } else {
      throw new Exception("User not found for userId : " + userId + " " + httpResponse.status + " :: " + httpResponse.body)
      null
    }
  }

  def readOrgKeys(rootOrgId: String, config: CertificatePreProcessorConfig): util.Map[String, AnyRef] = {
    val httpRequest = s"""{"request":{"organisationId":"${rootOrgId}"}}}"""
    val httpResponse = CertificatePreProcessorStreamTask.httpUtil.post(config.lmsBaseUrl + "/v1/org/read", httpRequest)
    if (httpResponse.status == 200) {
      println("Org read success: " + httpResponse.body)
      val response = mapper.readValue(httpResponse.body, classOf[util.Map[String, AnyRef]])
      val result = response.getOrDefault("result", new util.HashMap()).asInstanceOf[util.Map[String, AnyRef]]
      val keys = result.getOrDefault("keys", new util.HashMap()).asInstanceOf[util.Map[String, AnyRef]]
      if (MapUtils.isNotEmpty(keys) && CollectionUtils.isNotEmpty(keys.get("signKeys").asInstanceOf[util.List[util.Map[String, AnyRef]]])) {
        val signKeys = new util.HashMap[String, AnyRef]() {
          {
            put("id", keys.get("signKeys").asInstanceOf[util.List[util.Map[String, AnyRef]]].get(0))
          }
        }
        signKeys
      }
      keys
    } else {
      throw new Exception("Error while reading organisation  : " + rootOrgId + " " + httpResponse.status + " :: " + httpResponse.body)
      null
    }
  }
}
