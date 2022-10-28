package com.osocron.paxos.node

import com.osocron.paxos.PaxosError
import com.osocron.paxos.core.{Promise, _}
import zio.console.{Console, putStrLn}
import zio._

case class NodeImpl[A](messageQueue: Queue[Protocol[A]],
                       leanerQueueRef: Ref[Queue[Protocol[A]]],
                       acceptedPrepareRequests: Ref[List[PrepareRequest[A]]],
                       acceptedProposals: Ref[List[Proposal[A]]],
                       receivedPromises: Ref[List[Promise[A]]],
                       receivedAcceptedProposals: Ref[List[ProposalAccepted[A]]],
                       proposedProposals: Ref[List[Proposal[A]]],
                       majorityN: Int,
                       isProposer: Boolean,
                       isLearner: Boolean,
                       availableProposalIds: Ref[LazyList[Int]]) extends Node.Service[A] {

  def run: URIO[Console, Fiber.Runtime[PaxosError, Nothing]] = {
    val loop: ZIO[Console, PaxosError, Unit] = for {
      message <- messageQueue.take
      _ <- console.putStrLn("Taking a message from the queue...")
      _ <- handleMessage(message)
    } yield ()

    loop.forever.fork
  }

  def handleMessage(protocol: Protocol[A]): ZIO[Console, PaxosError, Unit] = protocol match {
    case i@InitiateProposal(_, _) => handleInitiateProposal(i)
    case p@PrepareRequest(_, _) => handlePrepareRequest(p)
    case p@Promise(_, _, _) => handlePromise(p)
    case a@AcceptRequest(_, _) => handleAcceptRequest(a)
    case p@ProposalAccepted(_) => handleProposalAccepted(p)
  }

  def handleInitiateProposal(i: InitiateProposal[A]): ZIO[Console, PaxosError, Unit] =
    if (isProposer) {
      for {
        _ <- putStrLn(s"As a proposer, I'm about to send a prepare request to all nodes in the cluster!")

        ids <- availableProposalIds.get
        nextId = ids.head
        _ <- putStrLn(s"The next available id is: $nextId")
        _ <- availableProposalIds.update(l => l.tail)

        _ <- putStrLn(s"Creating proposal with id: $nextId and value: ${i.value}")
        proposal = Proposal(nextId, i.value)

        _ <- putStrLn(s"Updating list of sent proposals with newly created proposal")
        _ <- proposedProposals.update(l => proposal :: l)

        _ <- ZIO.foreachPar_(i.cluster)(q => for {
          _ <- putStrLn(s"Sending prepare request with id: $nextId to a node")
          _ <- q.offer(PrepareRequest[A](nextId, messageQueue))
        } yield ())

      } yield ()
    } else putStrLn("I am not a proposer, cannot initiate a proposal :(")

  def handlePrepareRequest(p: PrepareRequest[A]): ZIO[Console, PaxosError, Unit] = for {
    _ <- putStrLn(s"Got prepare request with id ${p.proposalN}")

    currentAcceptedPrepareRequests <- acceptedPrepareRequests.get
    highestNumberedPrepareRequest = currentAcceptedPrepareRequests.sortBy(_.proposalN)(Ordering[Int].reverse).headOption
    _ <- putStrLn(s"My current highest accepted prepare request is $highestNumberedPrepareRequest")

    acceptedProposals <- acceptedProposals.get
    maybeHighestAcceptedProposal = acceptedProposals.sortBy(_.id)(Ordering[Int].reverse).headOption
    _ <- putStrLn(s"My current highest accepted proposal is $maybeHighestAcceptedProposal")

    _ <-
      if (highestNumberedPrepareRequest.isEmpty)
        acceptedPrepareRequests.set(List(p)) *>
          p.senderQueue.offer(Promise(p.proposalN, None, messageQueue))
            .zipLeft(putStrLn(s"Sent a promise with number: ${p.proposalN} cause I had not accepted any prepare requests before :)"))

      else if (p.proposalN > highestNumberedPrepareRequest.get.proposalN)
        acceptedPrepareRequests.update(current => p :: current) *>
          p.senderQueue.offer(Promise(p.proposalN, maybeHighestAcceptedProposal, messageQueue))
            .zipLeft(putStrLn(s"Sent a promise with number: ${p.proposalN} and previous accepted proposal ${maybeHighestAcceptedProposal} because the number was higher than a previously accepter prepare request with number: ${highestNumberedPrepareRequest.get.proposalN}"))

      else putStrLn(s"Did not accept proposal with number ${p.proposalN}")

  } yield ()

  def handlePromise(p: Promise[A]): ZIO[Console, PaxosError, Unit] =
    if (!isProposer) putStrLn("Why did I get this??? I'm not a proposer!")
    else for {

      _ <- putStrLn(s"I'm a proposer and I got a promise with number: ${p.proposalId} and previous accepted proposal: ${p.previousAcceptedProposal}")
      _ <- receivedPromises.update(xs => p :: xs)
      promises <- receivedPromises.get


      _ <-
        if (promises.count(_.proposalId == p.proposalId) >= majorityN) {

          val previousNonEmptyProposals = promises.flatMap(_.previousAcceptedProposal)
          val highestProposal = previousNonEmptyProposals.sortBy(_.id)(Ordering[Int].reverse).headOption

          // Send to all acceptors an accept request with the highest numbered proposal if any.
          putStrLn(s"I have received a promise from a majority of the cluster! Sending an accept request to all nodes") *>
            ZIO.foreachPar_(promises) { promise =>
              for {
                propProposals <- proposedProposals.get
                maybeCurrentProposal = propProposals.find(p => p.id == promise.proposalId)
                _ <- putStrLn(s"The current proposal we are talking about is $maybeCurrentProposal with id: ${promise.proposalId}")

                _ <- maybeCurrentProposal.fold(putStrLn("This should not happen! :(") *> ZIO.succeed(true)) { currProposal =>

                  if (previousNonEmptyProposals.nonEmpty)
                    promise.senderQueue.offer(AcceptRequest(Proposal(currProposal.id, highestProposal.get.value), messageQueue))
                      .zipLeft(putStrLn(s"Sent an accept request with id ${currProposal.id} the highest proposal $highestProposal"))
                  else
                    promise.senderQueue.offer(AcceptRequest(currProposal, messageQueue))
                      .zipLeft(putStrLn(s"Seems no one in the cluster had accepted a proposal before, sending what I have: $currProposal"))

                }
              } yield ()
            }
        }
        else putStrLn("Have not received a majority of promises :(")
    } yield ()

  def handleAcceptRequest(request: AcceptRequest[A]): ZIO[Console, PaxosError, Unit] = for {
    _ <- putStrLn(s"Received an accept request with number: ${request.proposal.id} and value ${request.proposal.value}! Processing...")

    currPrepareR <- acceptedPrepareRequests.get
    highestPrepareR = currPrepareR.sortBy(_.proposalN)(Ordering[Int].reverse).headOption
    _ <- putStrLn(s"My current highest accepted prepare request is $highestPrepareR")

    learner <- leanerQueueRef.get

    _ <-
      if (highestPrepareR.isEmpty)
        acceptedProposals.update(l => request.proposal :: l) *>
          learner.offer(ProposalAccepted(request.proposal))
          .zipLeft(putStrLn("Accepted the accept request because I had not accepted anything before :)"))

      else if (highestPrepareR.get.proposalN > request.proposal.id)
        putStrLn("Not accepting request, because proposal number is less than what I promised!")

      else
        acceptedProposals.update(l => request.proposal :: l) *>
          learner.offer(ProposalAccepted(request.proposal))
          .zipLeft(putStrLn(s"Accepted the accept request because proposal with number: ${request.proposal.id} is greater or the same as what I had promised with number: ${highestPrepareR.get.proposalN} :)"))
  } yield ()

  def handleProposalAccepted(p: ProposalAccepted[A]): ZIO[Console, PaxosError, Unit] = {
    if (!isLearner) putStrLn("I'm not a learner, should not know about his!")
    else {
      for {
        _ <- receivedAcceptedProposals.update(l => p :: l)
        total <- receivedAcceptedProposals.get

        _ <-
          if (total.count(_.proposal.id == p.proposal.id) >= majorityN)
            putStrLn(s"--------------------------WE HAVE CONSENSUS!---------------------------") *>
              putStrLn(s"-----------The reached consensus is proposal with id: ${p.proposal.id} and value: ${p.proposal.value}-------------")
          else putStrLn(s"No consensus has been reached yet...")
      } yield ()
    }
  }

}

object NodeImpl {
  def make[A](majorityN: Int,
              isProposer: Boolean,
              isLearner: Boolean,
              leanerQueueRef: Ref[Queue[Protocol[A]]],
              availableProposalIds: Ref[LazyList[Int]]): ZIO[Console, Nothing, (Fiber.Runtime[PaxosError, Nothing], Queue[Protocol[A]])] = for {

    queue <- Queue.unbounded[Protocol[A]]
    acceptedPrepareRequests <- Ref.make(List.empty[PrepareRequest[A]])
    acceptedProposals <- Ref.make(List.empty[Proposal[A]])
    proposedProposals <- Ref.make(List.empty[Proposal[A]])
    receivedAcceptedProposals <- Ref.make(List.empty[ProposalAccepted[A]])
    promises <- Ref.make(List.empty[Promise[A]])

    node = NodeImpl(queue, leanerQueueRef, acceptedPrepareRequests, acceptedProposals, promises, receivedAcceptedProposals, proposedProposals, majorityN, isProposer, isLearner, availableProposalIds)
    fiber <- node.run

  } yield (fiber, queue)
}
