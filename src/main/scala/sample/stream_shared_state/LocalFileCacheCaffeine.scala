package sample.stream_shared_state

import java.io.File
import java.net.URI
import java.nio.file.{Path, Paths}
import java.util
import java.util.concurrent.ThreadLocalRandom

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, MergePrioritized, Sink, Source}
import akka.stream.{ActorAttributes, ActorMaterializer, Supervision, ThrottleMode}
import com.github.benmanes.caffeine
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.http.client.HttpResponseException
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal


/**
  * Implement a local file cache with caffeine https://github.com/ben-manes/caffeine
  * Use scala wrapper scaffeine for type convenience: https://github.com/blemale/scaffeine
  * Use CacheWriter to write .zip file to file storage
  *
  * Before running this class, start alpakka.env.FileServer as (faulty) HTTP download mock
  * Monitor localFileCache dir with:  watch ls -ltr
  *
  * Use case:
  * Process a stream of messages with reoccurring TRACE_ID
  * For the first TRACE_ID download a .zip file from the FileServer and add the Path to the local cache
  * For each subsequent TRACE_IDs try first to load from the local file cache to avoid duplicate downloads per TRACE_ID
  * On downstream error, the file needs to be kept longer in the local cache
  * On system restart: read all files from filesystem to cache (for now: ordered by lastModified)
  */
object LocalFileCacheCaffeine {
  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  val deciderFlow: Supervision.Decider = {
    case NonFatal(e) =>
      val rootCause = ExceptionUtils.getRootCause(e)
      logger.error(s"Stream failed with: $rootCause, going to restart")
      logger.debug(s"Stream failed with: $rootCause, going to restart", e)
      Supervision.Restart
    case _ => Supervision.Stop
  }

  val scaleFactor = 1 //Raise to stress more
  val evictionTime = 5.minutes //Lower eg to 5.seconds to see cache and file system deletes
  val evictionTimeOnError = 10.minutes
  val localFileCache = Paths.get(System.getProperty("java.io.tmpdir")).resolve("localFileCache")

  logger.info(s"Starting with localFileCache dir: $localFileCache")
  FileUtils.forceMkdir(localFileCache.toFile)
  //Comment out to start with empty local file storage on restart
  FileUtils.cleanDirectory(localFileCache.toFile)


  val writer = new caffeine.cache.CacheWriter[Int, Path] {
    override def write(key: Int, value: Path): Unit = {
      logger.debug(s"TRACE_ID: $key write to cache")
    }

    override def delete(key: Int, value: Path, cause: caffeine.cache.RemovalCause): Unit = {
      logger.info(s"TRACE_ID: $key delete from file system because of: $cause")
      val destinationFile = localFileCache.resolve(value)
      FileUtils.deleteQuietly(destinationFile.toFile)
    }
  }

  val cache: Cache[Int, Path] =
    Scaffeine()
      .recordStats()
      .expireAfter[Int, Path]((_, _) => evictionTime, (_, _, _) => evictionTimeOnError, (_, _, _) => evictionTime)
      .maximumSize(1000)
      .writer(writer)
      .build[Int, Path]()

  //Do "manual loading" for now
  val loadedResults: util.List[File] = new FileLister().run(localFileCache.toFile)
  loadedResults.forEach { each: File =>
    logger.debug(s"Add file: ${each.getName} with lastModified: ${each.lastModified()}")
    cache.put(each.getName.dropRight(4).toInt, each.toPath)
  }

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("LocalFileCacheCaffeine")
    implicit val materializer = ActorMaterializer()

    case class Message(group: Int, id: Int, file: Path)

    val downloadFlow: Flow[Message, Message, NotUsed] = Flow[Message]
      .mapAsyncUnordered(5) { message =>

        def processNext(message: Message): Message = {

          //Using get(key => downloadfunction) is preferable to getIfPresent, because the get method performs the computation atomically
          //Thus there are no 404 anymore from server - exactly what we need
          val key = message.id
          val url = new URI("http://127.0.0.1:6001/downloadni/" + key.toString)
          val destinationFile = localFileCache.resolve(Paths.get(message.id.toString + ".zip"))
          val downloadedFile = cache.get(message.id, key => new DownloaderRetry().download(key, url, destinationFile))
          logger.info(s"TRACE_ID: ${message.id} successfully read from cache")
          Message(message.group, message.id, downloadedFile)

          //          if (cache.getIfPresent(message.id).isDefined) {
          //            val value = cache.getIfPresent(message.id).get
          //            logger.info(s"Cache hit for TRACE_ID: ${message.id}")
          //            Message(message.group, message.id, value)
          //          } else {
          //            logger.info(s"TRACE_ID: ${message.id} Cache miss - download...")
          //
          //              val destinationFile = localFileCache.resolve(Paths.get(message.id.toString + ".zip"))
          //              //val url = new URI("http://127.0.0.1:6001/downloadflaky/" + message.id.toString)
          //              val url = new URI("http://127.0.0.1:6001/downloadni/" + message.id.toString)
          //              val downloadedFile = new DownloaderRetry().download(url, destinationFile)
          //              logger.info(s"TRACE_ID: ${message.id} Successfully downloaded - put into cache...")
          //              cache.put(message.id, downloadedFile)
          //              Message(message.group, message.id, downloadedFile)
          //          }
        }
        //If (for whatever reason) we get a 404 use this "optimistic approach":
        //Wait for the file to appear in the local file cache
        Future(processNext(message)).recoverWith {
          case e: RuntimeException if ExceptionUtils.getRootCause(e).isInstanceOf[HttpResponseException] => {
            val rootCause = ExceptionUtils.getRootCause(e)
            val status = rootCause.asInstanceOf[HttpResponseException].getStatusCode
            val resultPromise = Promise[Message]()
            if (status == 404) {
              logger.info(s"TRACE_ID: ${message.id} Request failed with 404, wait for the file to appear in the local cache (from a concurrent download)")
              Thread.sleep(10000) //TODO Do poll
              if (cache.getIfPresent(message.id).isDefined) {
                val value = cache.getIfPresent(message.id).get
                logger.info(s"TRACE_ID: ${message.id} CACHE hit")
                resultPromise.success(Message(message.group, message.id, value))
              } else {
                logger.info(s"TRACE_ID: ${message.id} CACHE miss")
                resultPromise.failure(e)
              }
            }
            resultPromise.future
          }
          case e: Throwable => Future.failed(e)
        }
      }

    val faultyDownstreamFlow: Flow[Message, Message, NotUsed] = Flow[Message]
      .map { message: Message =>
        if (message.group == 0) {
          //Force an update (= replacement of value) to extend time in cache. evictionTimeOnError will be used
          logger.info(s"TRACE_ID: ${message.id} extend eviction time for message in group: ${message.group}")
          cache.put(message.id, message.file)

        }
        message
      }

    //Generate random messages with IDs between 0/50 note that after a while we will have only cache hits
    //Do it like this to have a higher chance of concurrent messages (= same id)
    val messagesGroupZero = List.fill(10000)(Message(0, ThreadLocalRandom.current.nextInt(0, 50 * scaleFactor), null))
    val messagesGroupOne = List.fill(10000)(Message(1, ThreadLocalRandom.current.nextInt(0, 50 * scaleFactor), null))
    val messagesGroupTwo = List.fill(10000)(Message(2, ThreadLocalRandom.current.nextInt(0, 50 * scaleFactor), null))

    val combinedMessages = Source.combine(Source(messagesGroupZero), Source(messagesGroupOne), Source(messagesGroupTwo))(numInputs => MergePrioritized(List(1, 1, 1)))
    combinedMessages
      .throttle(2 * scaleFactor, 1.second, 2 * scaleFactor, ThrottleMode.shaping)
      //Try to go parallel on the TRACE_ID and thus have 2 substreams
      .groupBy(2, _.id % 2)
      .via(downloadFlow)
      //.via(faultyDownstreamFlow)
      .mergeSubstreams
      .withAttributes(ActorAttributes.supervisionStrategy(deciderFlow))
      .runWith(Sink.ignore)
  }
}