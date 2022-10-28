package com.osocron.paxos

import com.osocron.paxos.core.Protocol
import zio.console.Console
import zio.{Has, ZIO}

package object node {

  type Node[A] = Has[Node.Service[A]]

  object Node {
    trait Service[A] {
      def handleMessage(protocol: Protocol[A]): ZIO[Console, PaxosError, Unit]
    }
  }

}
