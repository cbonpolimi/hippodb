/*  Copyright 2014 UniCredit S.p.A.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package unicredit.hippo
package actors

import scala.concurrent.duration._
import scala.concurrent.Future

import akka.actor._
import akka.cluster.{ Cluster, Member, MemberStatus }
import akka.cluster.ClusterEvent._
import akka.pattern.pipe
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import scalaz.Scalaz._
import com.google.common.cache.{ CacheBuilder, CacheLoader }
import net.ceedubs.ficus.FicusConfig._

import messages._
import common.shards
import pattern.{ fixedAsk, fallback }


class Frontend(retriever: ActorRef) extends Actor with ActorLogging {
  private val config = ConfigFactory.load
  private val id = config.as[String]("storage.local-id")
  private val duration = config.as[FiniteDuration]("request.timeout")
  private val replicas = config.as[Int]("storage.replicas")
  private val cacheSize = config.as[Int]("storage.cache-size")
  // The timeout is set so that in the worst case we have time
  // to ask a value to each replica before the whole request
  // times out. 95% is here 19/20 because
  //   FiniteDuration#*(factor: Double)
  // returns Duration and not FiniteDuration
  implicit val timeout = Timeout(duration * 19 div (20 * replicas))
  import context.dispatcher

  val cluster = Cluster(context.system)
  var siblings = Map(id -> self)

  override def preStart() = cluster.subscribe(self, classOf[MemberUp])
  override def postStop() = cluster.unsubscribe(self)

  val failedFuture = Future.failed(new Exception("No future completed"))
  // A simple cache for requests. We directly cache instances of
  // Future[Result] instead of waiting for the responses, then
  // caching the Result. This is probably less efficient in terms
  // of RAM usage, but it is easier and does not incur in race
  // conditions in the case where many identical requests are fired
  // concurrently. It is also safer with respect to race conditions
  // during version switches.
  val cache = CacheBuilder
    .newBuilder
    .maximumSize(cacheSize)
    .build(new CacheLoader[Request, Future[Result]] {
      def load(request: Request) = {
        val Request(table, keys, columns) = request
        // We make a separate request for each key. This is less
        // efficient than grouping the keys by shard and issuing
        // a single request to each server, but it has the advantage
        // that it is very easy to deal with failures by looking for
        // the key in the next shard. Implementation may change in the
        // future if this becomes a performance issue.
        val results = keys map { key ⇒
          val message = Retrieve(table, List(key), columns)
          // Remotes are sorted so that the local server is put first, if present
          val remotes = sort(shards(key, siblings.keySet.toSeq, replicas)).toStream
          // Change this with a proper implementation of streams
          // that does not force its first element.
          // Right now we just insert a dummy element at the head
          // of the stream to avoid firing a remore request if
          // it is not needed.
          val lazyRequests = failedFuture #:: (remotes map { id ⇒
            log.info(s"Sending request to $id for key $key")
            // If the result set is empty, the key was not found,
            // and we should count this as a failed request
            (siblings(id) ? message).mapTo[Result] filter (! _.content.isEmpty)
          })

          firstOf(lazyRequests)(df = Result(Map()))
        }
        Future.sequence(results) map accumulate
      }
    })

  // Puts id in front of other indices, if present
  private def sort(indices: List[String]) =
    if (indices contains id) id :: indices.filterNot(id ==)
    else indices

  def receive = {
    // Membership messages
    //********************
    case MemberUp(member) ⇒
      log.info(s"Recognized new member $member")
      val frontend = siblingActor(member)

      frontend ! IdentifyTo(self)
      frontend ! MyIdIs(self, id)
    case MyIdIs(actor, id) ⇒
      siblings += (id -> actor)
      context watch sender
    case Terminated(actor) ⇒
      log.info(s"Lost contact with actor $actor")
      siblings = siblings filterNot { case (_, a) ⇒ a == actor }
    // Actual request messages
    //************************
    case m: Request ⇒
      cache get m pipeTo sender
    case m: Retrieve ⇒
      retriever ? m pipeTo sender
    case m: Switch ⇒
      retriever ! m
      cache.invalidateAll
    case GetSiblings ⇒
      sender ! Siblings(siblings)
    case x ⇒ log.info(s"Ignored message $x")
  }

  def firstOf[A](futures: ⇒ Stream[Future[A]])(implicit df: A): Future[A] = futures match {
    case Stream() ⇒ Future(df)
    // One may think to match h #:: t, but this would eagerly
    // evaluate the head of t, firing one more request than
    // needed.
    case stream ⇒ stream.head orElse firstOf(stream.tail)
    // Unfortunately, Future#fallbackTo evaluates its argument
    // eagerly, and this would force us to spawn more requests
    // than needed. Hence the use of Future#recoverWith, as in
    // the fallback pattern.
  }

  def siblingActor(member: Member) = {
    val path = RootActorPath(member.address) / "user" / "frontend"

    context.actorSelection(path)
  }

  def accumulate(results: Iterable[Result]) =
    results.foldLeft(Result(Map())) { case (Result(m1), Result(m2)) ⇒
      Result(m1 |+| m2)
    }
}