package com.osocron.paxos

import com.osocron.paxos.core.{InitiateProposal, Protocol}
import com.osocron.paxos.node.NodeImpl
import zio._
import zio.console.{Console, putStrLn}

object App extends zio.App {

  val program: ZIO[Console, PaxosError, Unit] = for {
    _ <- putStrLn("Starting loop")

    proposerIds <- Ref.make(LazyList.from(1, 100))
    acceptorEmptyIds <- Ref.make(LazyList.empty[Int])
    placeholderQueue <- Queue.bounded[Protocol[String]](1)
    learnerQueueRef <- Ref.make(placeholderQueue)

    proposerCtx <- NodeImpl.make[String](2, isProposer = true, isLearner = false, learnerQueueRef, proposerIds)
    (proposerFib, proposerQueue) = proposerCtx

    acceptorCtx <- NodeImpl.make[String](2, isProposer = false, isLearner = false, learnerQueueRef, acceptorEmptyIds)
    (_, acceptorQueue) = acceptorCtx

    learnerCtx <- NodeImpl.make[String](2, isProposer = false, isLearner = true, learnerQueueRef, acceptorEmptyIds)
    (_, learnerQueue) = learnerCtx

    _ <- learnerQueueRef.set(learnerQueue)

    cluster = List(proposerQueue, acceptorQueue, learnerQueue)

    _ <- putStrLn("About to send an initial proposal to the proposer")
    _ <- proposerQueue.offer(InitiateProposal("My awesome value", cluster)).fork

    //        _ <- ZIO.effect(Thread.sleep(1000)).orElseFail(UnexpectedError)

    _ <- putStrLn("About to send another initial proposal to the proposer")
    _ <- proposerQueue.offer(InitiateProposal("My other awesome value!", cluster)).fork

    _ <- putStrLn("About to send another initial proposal to the proposer")
    f <- proposerQueue.offer(InitiateProposal("My third awesome value!", cluster)).fork

//    _ <- f.interrupt

    _ <- proposerFib.join
  } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    program.exitCode

}
