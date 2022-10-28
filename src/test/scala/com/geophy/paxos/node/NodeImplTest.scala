package com.osocron.paxos.node

import com.osocron.paxos.PaxosError
import com.osocron.paxos.core._
import zio._
import zio.console.putStrLn
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestEnvironment

object NodeImplTest extends DefaultRunnableSpec {

  def spec: Spec[TestEnvironment, TestFailure[PaxosError], TestSuccess] = suite("NodeSpec") {

    testM("should work") {
      for {
        _ <- putStrLn("Starting loop")

        proposerIds <- Ref.make(LazyList.from(1, 100))
        placeholderQueue <- Queue.bounded[Protocol[String]](1)
        learnerQueueRef <- Ref.make(placeholderQueue)

        proposerCtx <- NodeImpl.make[String](1, isProposer = true, isLearner = true, learnerQueueRef, proposerIds)
        (proposerFib, proposerQueue) = proposerCtx

        _ <- learnerQueueRef.set(proposerQueue)

        cluster = List(proposerQueue)

        _ <- putStrLn("About to send an initial proposal to the proposer")
        _ <- proposerQueue.offer(InitiateProposal("A value", cluster)).fork

        _ <- proposerFib.join
      } yield assert(proposerQueue)(equalTo(proposerQueue))
    }

  }
}
