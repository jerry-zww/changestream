package changestream.actors

import java.io._
import java.nio.charset.StandardCharsets

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}
import akka.actor.{Actor, ActorRef, Cancellable}
import changestream.events.MutationWithInfo
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.model.{ObjectMetadata, PutObjectRequest, PutObjectResult}
import com.github.dwhjames.awswrap.s3.AmazonS3ScalaClient
import com.typesafe.config.{Config, ConfigFactory}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, Future}

object S3Actor {
  case class FlushRequest(origSender: ActorRef)
}

class S3Actor(config: Config = ConfigFactory.load().getConfig("changestream")) extends Actor {
  import S3Actor.FlushRequest

  protected val log = LoggerFactory.getLogger(getClass)
  protected implicit val ec = context.dispatcher

  protected val BUFFER_TEMP_DIR = config.getString("aws.s3.buffer-temp-dir")
  protected val bufferDirectory = new File(BUFFER_TEMP_DIR)
  protected val BUCKET = config.getString("aws.s3.bucket")
  protected val KEY_PREFIX = config.getString("aws.s3.key-prefix") match {
    case s if s.endsWith("/") => s
    case s => s"$s/"
  }
  protected val BATCH_SIZE = config.getLong("aws.s3.batch-size")
  protected val MAX_WAIT = config.getLong("aws.s3.flush-timeout").milliseconds
  protected val TIMEOUT = config.getInt("aws.timeout")

  protected var cancellableSchedule: Option[Cancellable] = None
  protected def setDelayedFlush(origSender: ActorRef) = {
    val scheduler = context.system.scheduler
    cancellableSchedule = Some(scheduler.scheduleOnce(MAX_WAIT) { self ! FlushRequest(origSender) })
  }
  protected def cancelDelayedFlush = cancellableSchedule.foreach(_.cancel())

  protected lazy val client = new AmazonS3ScalaClient(
    new DefaultAWSCredentialsProviderChain(),
    new ClientConfiguration().
      withConnectionTimeout(TIMEOUT),
    Regions.fromName(config.getString("aws.region"))
  )

  // Mutable State!!
  protected var currentBatchSize = 0
  protected var bufferFile: File = getNextFile
  protected var bufferWriter: BufferedWriter = getWriterForFile
  // End Mutable State!!

  // Wrap the Java IO
  protected def getNextFile = File.createTempFile("buffer", ".json", bufferDirectory)
  protected def getWriterForFile = {
    val streamWriter = new OutputStreamWriter(new FileOutputStream(bufferFile), StandardCharsets.UTF_8)
    new BufferedWriter(streamWriter)
  }

  protected def bufferMessage(message: String) = {
    bufferWriter.write(message)
    bufferWriter.newLine()
    currentBatchSize += 1
  }

  protected def getMetadata(length: Long, sseAlgorithm: String = "AES256") = {
    val metadata = new ObjectMetadata()
    metadata.setSSEAlgorithm(sseAlgorithm)
    metadata.setContentLength(length)
    metadata
  }

  protected def getMessageBatch = {
    val batchFile = bufferFile

    bufferWriter.close()
    currentBatchSize = 0
    bufferFile = getNextFile
    bufferWriter = getWriterForFile

    batchFile
  }

  protected def putFile(file: File, key: String = ""): Future[PutObjectResult] = {
    val objectKey = key match {
      case "" => file.getName
      case _ => key
    }
    val metadata = getMetadata(file.length)
    client.putObject(new PutObjectRequest(BUCKET, s"${KEY_PREFIX}${objectKey}", file).withMetadata(metadata))
  }

  override def preStart() = {
    val file = new File(s"${BUFFER_TEMP_DIR}test.txt")
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write("test")
    bw.close()

    val testPutFuture = putFile(file).failed.map {
      case exception:Throwable =>
        log.error(s"Failed to create test object in S3 bucket ${BUCKET} at key ${KEY_PREFIX}test.txt: ${exception.getMessage}")
        throw exception
    }

    Await.result(testPutFuture, TIMEOUT milliseconds)
    log.info(s"Ready to push messages to bucket ${BUCKET} with key prefix ${KEY_PREFIX}")
  }
  override def postStop() = {
    cancelDelayedFlush
    client.shutdown()
  }

  def receive = {
    case MutationWithInfo(mutation, _, _, Some(message: String)) =>
      log.debug(s"Received message: ${message}")

      cancelDelayedFlush

      bufferMessage(message)
      currentBatchSize match {
        case BATCH_SIZE => flush(sender())
        case _ => setDelayedFlush(sender())
      }

    case FlushRequest(origSender) =>
      flush(origSender)
  }

  protected def flush(origSender: ActorRef) = {
    log.debug(s"Flushing ${currentBatchSize} messages to S3.")

    val now = DateTime.now
    val datePrefix = f"${now.getYear}/${now.getMonthOfYear}%02d/${now.getDayOfMonth}%02d"
    val key = s"${datePrefix}/${System.nanoTime}-${currentBatchSize}.json"
    val file = getMessageBatch
    val request = putFile(file, key)

    val s3Url = s"${BUCKET}/${KEY_PREFIX}${key}"

    request onComplete {
      case Success(result: PutObjectResult) =>
        file.delete()
        log.info(s"Successfully saved ${currentBatchSize} messages (${file.length} bytes) to ${s3Url}.")
        origSender ! akka.actor.Status.Success(s3Url)
      case Failure(exception) =>
        log.error(s"Failed to save ${currentBatchSize} messages from ${file.getName} (${file.length} bytes) to ${s3Url}: ${exception.getMessage}")
        throw exception
    }
  }
}
