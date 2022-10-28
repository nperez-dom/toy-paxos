package com.osocron.paxos.core

import zio.Queue

sealed trait Protocol[A]
case class InitiateProposal[A](value: A, cluster: List[Queue[Protocol[A]]]) extends Protocol[A]
case class PrepareRequest[A](proposalN: Int, senderQueue: Queue[Protocol[A]]) extends Protocol[A]
case class Promise[A](proposalId: Int, previousAcceptedProposal: Option[Proposal[A]], senderQueue: Queue[Protocol[A]]) extends Protocol[A]
case class AcceptRequest[A](proposal: Proposal[A], senderQueue: Queue[Protocol[A]]) extends Protocol[A]
case class ProposalAccepted[A](proposal: Proposal[A]) extends Protocol[A]

case class Proposal[A](id: Int, value: A)
