package org.sunbird.job.deletioncleanup.domain

import java.util
import java.util.UUID

import scala.collection.JavaConverters._

case class ActorObject(id: String, `type`: String = "User")

case class EventContext(channel: String = "in.sunbird",
                        env: String = "User",
                        sid: String = UUID.randomUUID().toString,
                        did: String = UUID.randomUUID().toString,
                        pdata: util.Map[String, String] = Map("ver" -> "3.0", "id" -> "org.sunbird.learning.platform", "pid" -> "user-delete").asJava,
                        cdata: Array[util.Map[String, String]])


case class EventData(props: Array[String], `type`: String, status: String)

case class EventObject(id: String, `type`: String)

case class TelemetryEvent(actor: ActorObject,
                          eid: String = "AUDIT",
                          edata: EventData,
                          ver: String = "3.0",
                          syncts: Long = System.currentTimeMillis(),
                          ets: Long = System.currentTimeMillis(),
                          context: EventContext = EventContext(
                            cdata = Array[util.Map[String, String]]()
                          ),
                          mid: String = s"LP.AUDIT.${UUID.randomUUID().toString}",
                          `object`: EventObject,
                          tags: util.List[AnyRef] = new util.ArrayList[AnyRef]()
                         )
