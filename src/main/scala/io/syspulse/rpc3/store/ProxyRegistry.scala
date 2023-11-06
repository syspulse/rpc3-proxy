package io.syspulse.rpc3.store

import scala.util.{Try,Success,Failure}

import scala.collection.immutable
import com.typesafe.scalalogging.Logger
import io.jvm.uuid._

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

import io.syspulse.skel.Command

import io.syspulse.rpc3._
import io.syspulse.rpc3.server._


object ProxyRegistry {
  val log = Logger(s"${this}")

  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    
  final case class ProxyRpc(req:String,replyTo: ActorRef[String]) extends Command  
  
  def apply(store: ProxyStore): Behavior[io.syspulse.skel.Command] = {
    registry(store)
  }

  private def registry(store: ProxyStore): Behavior[io.syspulse.skel.Command] = {    
    
    Behaviors.receiveMessage {

      case ProxyRpc(req,replyTo) =>
        
        val f = store.rpc(req)

        f.onComplete(r => r match {
          case Success(rsp) => replyTo ! rsp
          case Failure(e) => 
            log.error(s"${e}")
            throw e
        })

        Behaviors.same
    }
  }
}
