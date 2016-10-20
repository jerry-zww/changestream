package changestream

import java.io.IOException

import akka.actor.ActorSystem
import com.github.shyiko.mysql.binlog.BinaryLogClient
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

object ChangeStream extends App {
  protected val log = LoggerFactory.getLogger(getClass)
  protected val system = ActorSystem("changestream")

  protected val config = ConfigFactory.load().getConfig("changestream")
  protected lazy val client = new BinaryLogClient(
    config.getString("mysql.host"),
    config.getInt("mysql.port"),
    config.getString("mysql.user"),
    config.getString("mysql.password")
  )

  /** Every changestream instance must have a unique server-id.
    *
    * http://dev.mysql.com/doc/refman/5.7/en/replication-setup-slaves.html#replication-howto-slavebaseconfig
    */
  client.setServerId(config.getLong("mysql.server-id"))

  /** If we lose the connection to the server retry every `changestream.mysql.keepalive` milliseconds. **/
  client.setKeepAliveInterval(config.getLong("mysql.keepalive"))

  /** Register the objects that will receive `onEvent` calls and deserialize data **/
  ChangeStreamEventListener.setConfig(config)
  client.registerEventListener(ChangeStreamEventListener)
  client.setEventDeserializer(ChangestreamEventDeserializer)

  /** Register the object that will receive BinaryLogClient connection lifecycle events **/
  client.registerLifecycleListener(ChangeStreamLifecycleListener)

  /** Gracefully handle application shutdown from
    *  - Normal program exit
    *  - TERM signal
    *  - System reboot/shutdown
    */
  sys.addShutdownHook({
    log.info("Shutting down...")

    /** Disconnect the BinaryLogClient and stop processing events **/
    client.disconnect()

    /** Give the changestream actor system plenty of time to finish processing events **/
    Await.result(system.terminate(), 60 seconds)
  })

  /** Finally, signal the BinaryLogClient to start processing events **/
  log.info(s"Starting changestream...")
  while(!client.isConnected) {
    try {
      client.connect()
    }
    catch {
      case e: IOException =>
        log.error(e.getMessage)
        log.error("Failed to connect to MySQL to stream the binlog, retrying...")
        Thread.sleep(5000)
      case e: Exception =>
        log.error("Failed to connect, exiting.", e)
        System.exit(1)
    }
  }
}
